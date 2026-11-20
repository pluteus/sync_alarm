package com.pluteus.syncalarm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Firestore から最新のアラーム一覧を取得して AlarmManager に登録し直すワーカー。
 * - 定期実行（15分間隔）：スリープ中に他端末で追加/変更されたアラームを拾う
 * - 単発実行：起動時・鳴動直後・アプリ起動時など
 * 正確な発火そのものは AlarmManager が担うので、ワーカーの遅延は「新しいアラームの反映」だけに影響する。
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val uid = Repo.uid() ?: return Result.success() // 未ログインなら何もしない
        return try {
            val alarms = Repo.fetchAlarms(uid)
            AlarmScheduler.rescheduleAll(applicationContext, alarms)
            // 同期設定（音ON/OFF）も取り込んで端末ローカルに反映する
            Repo.fetchSoundEnabled(uid)?.let { Prefs.setSoundEnabled(applicationContext, it) }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun enqueueOneTime(ctx: Context) {
            try {
                WorkManager.getInstance(ctx).enqueueUniqueWork(
                    "sync_once",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<SyncWorker>().build()
                )
            } catch (e: Exception) { }
        }

        fun enqueuePeriodic(ctx: Context) {
            try {
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    "sync_periodic",
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
                )
            } catch (e: Exception) { }
        }
    }
}
