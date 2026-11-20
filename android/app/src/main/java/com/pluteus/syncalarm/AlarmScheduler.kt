package com.pluteus.syncalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * AlarmManager への正確なアラーム登録。
 * setAlarmClock（時計アラーム扱い）を使うため、スリープ中・Doze中でも時刻どおりに発火する。
 * 発火先は RingService への ForegroundService PendingIntent（Android 12以降でも
 * 正確アラームからのフォアグラウンドサービス起動は許可されている）。
 */
object AlarmScheduler {
    private const val SP = "sched_state"
    private const val KEY_IDS = "scheduled_ids"
    private const val SNOOZE_CODE = -1001

    private fun ringIntent(ctx: Context, alarmId: String?, time: String, label: String, kind: String): Intent =
        Intent(ctx, RingService::class.java).apply {
            action = RingService.ACTION_RING
            putExtra(RingService.EXTRA_ALARM_ID, alarmId)
            putExtra(RingService.EXTRA_TIME, time)
            putExtra(RingService.EXTRA_LABEL, label)
            putExtra(RingService.EXTRA_KIND, kind)
        }

    private fun pending(ctx: Context, code: Int, intent: Intent): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getForegroundService(ctx, code, intent, flags)
    }

    fun rescheduleAll(ctx: Context, alarms: List<AlarmItem>) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val oldIds = sp.getStringSet(KEY_IDS, emptySet())?.toSet() ?: emptySet()
        val now = System.currentTimeMillis()
        val newIds = mutableSetOf<String>()

        for (a in alarms) {
            if (!a.enabled) continue
            val at = a.nextFire(now) ?: continue
            val pi = pending(ctx, a.id.hashCode(), ringIntent(ctx, a.id, a.time, a.label, "alarm"))
            scheduleExact(ctx, am, at, pi)
            newIds.add(a.id)
        }
        // 消えた／無効になったアラームを取り消す（extrasは PendingIntent の同一判定に含まれない）
        for (id in oldIds - newIds) {
            val pi = pending(ctx, id.hashCode(), Intent(ctx, RingService::class.java).apply { action = RingService.ACTION_RING })
            try { am.cancel(pi) } catch (e: Exception) { }
            pi.cancel()
        }
        sp.edit().putStringSet(KEY_IDS, newIds).apply()
    }

    fun scheduleSnooze(ctx: Context, atMillis: Long, timeText: String, label: String) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx, SNOOZE_CODE, ringIntent(ctx, null, timeText, label, "snooze"))
        scheduleExact(ctx, am, atMillis, pi)
    }

    fun cancelSnooze(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx, SNOOZE_CODE, Intent(ctx, RingService::class.java).apply { action = RingService.ACTION_RING })
        try { am.cancel(pi) } catch (e: Exception) { }
        pi.cancel()
    }

    private fun scheduleExact(ctx: Context, am: AlarmManager, at: Long, pi: PendingIntent) {
        val show = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            }
        } catch (e: SecurityException) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } catch (e2: Exception) { }
        }
    }
}
