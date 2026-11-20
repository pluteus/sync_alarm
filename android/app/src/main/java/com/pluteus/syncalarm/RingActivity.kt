package com.pluteus.syncalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pluteus.syncalarm.databinding.ActivityRingBinding
import kotlinx.coroutines.launch

/** 鳴動画面。ロック画面の上にも表示され、画面を点灯させる。停止/スヌーズは Firestore 経由で全端末に伝わる。 */
class RingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRingBinding
    private var alarmId: String? = null
    private var timeText: String = ""
    private var label: String = ""

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        binding = ActivityRingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        alarmId = intent?.getStringExtra(RingService.EXTRA_ALARM_ID)
        timeText = intent?.getStringExtra(RingService.EXTRA_TIME) ?: ""
        label = intent?.getStringExtra(RingService.EXTRA_LABEL) ?: ""
        val kind = intent?.getStringExtra(RingService.EXTRA_KIND) ?: "alarm"

        binding.ringTimeText.text = timeText
        binding.ringLabelText.text = label
        binding.testBadge.visibility = if (kind == "test") View.VISIBLE else View.GONE

        val filter = IntentFilter(RingService.FINISH_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finishReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(finishReceiver, filter)
        }

        binding.stopBtn.setOnClickListener {
            val uid = Repo.uid()
            if (uid != null) {
                lifecycleScope.launch { Repo.writeStop(uid, Prefs.deviceName(this@RingActivity), alarmId) }
            }
            // サービスが state 変化で止まるが、オフライン時でも確実に止めるため直接も止める
            startService(Intent(this, RingService::class.java).setAction(RingService.ACTION_LOCAL_STOP))
            finish()
        }
        binding.snoozeBtn.setOnClickListener {
            val uid = Repo.uid()
            val until = System.currentTimeMillis() + 5 * 60 * 1000L
            if (uid != null) {
                lifecycleScope.launch { Repo.writeSnooze(uid, Prefs.deviceName(this@RingActivity)) }
            }
            AlarmScheduler.scheduleSnooze(this, until, timeText, label)
            startService(Intent(this, RingService::class.java).setAction(RingService.ACTION_LOCAL_STOP))
            finish()
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(finishReceiver) } catch (e: Exception) { }
        super.onDestroy()
    }
}
