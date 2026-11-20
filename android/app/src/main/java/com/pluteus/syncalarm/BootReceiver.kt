package com.pluteus.syncalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 再起動・時刻変更・アプリ更新後に、アラームのスケジュールを組み直す。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        SyncWorker.enqueueOneTime(context)
        SyncWorker.enqueuePeriodic(context)
    }
}
