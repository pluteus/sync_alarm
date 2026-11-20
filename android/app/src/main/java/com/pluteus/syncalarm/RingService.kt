package com.pluteus.syncalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 鳴動中のフォアグラウンドサービス。
 * - 端末のアラーム音量ストリームでループ再生＋バイブレーション（音OFFでもバイブは作動）
 * - Firestore の state/current を監視し、どの端末で止めても（PWA含む）即座に止まる
 * - 10分で自動停止（PWA版と同じ）
 */
class RingService : Service() {
    companion object {
        const val ACTION_RING = "com.pluteus.syncalarm.RING"
        const val ACTION_LOCAL_STOP = "com.pluteus.syncalarm.LOCAL_STOP"
        const val EXTRA_ALARM_ID = "alarmId"
        const val EXTRA_TIME = "time"
        const val EXTRA_LABEL = "label"
        const val EXTRA_KIND = "kind" // alarm | snooze | test
        const val FINISH_ACTION = "com.pluteus.syncalarm.RING_FINISH"
        @Volatile var isRinging: Boolean = false
    }

    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stateReg: ListenerRegistration? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)
    private var alarmId: String? = null
    private var label: String = ""
    private var timeText: String = ""
    private var kind: String = "alarm"
    private var startedAtMs: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LOCAL_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        alarmId = intent?.getStringExtra(EXTRA_ALARM_ID)
        timeText = intent?.getStringExtra(EXTRA_TIME) ?: ""
        label = intent?.getStringExtra(EXTRA_LABEL) ?: ""
        kind = intent?.getStringExtra(EXTRA_KIND) ?: "alarm"
        startedAtMs = System.currentTimeMillis()
        isRinging = true

        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()
        if (Prefs.soundEnabled(this)) startSound()
        startVibration()
        openRingActivity()
        watchState()
        // 次回分の再スケジュール（繰り返しアラームの翌回・同期取り直し）
        SyncWorker.enqueueOneTime(this)
        // 10分自動停止
        handler.postDelayed({
            val uid = Repo.uid()
            if (uid != null) scope.launch { Repo.writeStop(uid, "（自動停止）", alarmId) }
            stopRinging()
        }, 10 * 60 * 1000L)
        // 鳴動開始を全体へ通知（テスト鳴動は PWA 側で既に書かれているので上書きしない）
        val uid = Repo.uid()
        if (uid != null && kind != "test") {
            Repo.writeRinging(uid, alarmId, label, timeText, Prefs.deviceName(this))
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "アラーム鳴動", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "同期アラームの鳴動通知"
                setSound(null, null)
            }
            nm?.createNotificationChannel(ch)
        }
        val full = Intent(this, RingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TIME, timeText)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_KIND, kind)
        }
        val fullPi = PendingIntent.getActivity(
            this, 1, full,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(if (kind == "test") "テスト鳴動中" else "⏰ " + (label.ifEmpty { "アラーム" }))
            .setContentText(timeText)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .build()
    }

    private fun openRingActivity() {
        val i = Intent(this, RingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TIME, timeText)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_KIND, kind)
        }
        try { startActivity(i) } catch (e: Exception) { /* フルスクリーン通知側に任せる */ }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "syncalarm:ring").apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L)
            }
        } catch (e: Exception) { }
    }

    private fun startSound() {
        try {
            var uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@RingService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) { /* 音が出なくてもバイブと画面で知らせる */ }
    }

    private fun startVibration() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 300, 120, 300, 120, 500, 400)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) { }
    }

    /** Firestore の state を監視：ringing=false になったら（誰が止めても）止まる。 */
    private fun watchState() {
        val uid = Repo.uid() ?: return
        val doc = Repo.stateDoc(uid) ?: return
        stateReg?.remove()
        stateReg = doc.addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists()) return@addSnapshotListener
            val ringing = snap.getBoolean("ringing") ?: false
            if (!ringing) stopRinging()
        }
    }

    private fun stopRinging() {
        if (!isRinging && player == null) {
            stopSelf()
            return
        }
        isRinging = false
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        try { vibrator?.cancel() } catch (e: Exception) { }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
        handler.removeCallbacksAndMessages(null)
        sendBroadcast(Intent(FINISH_ACTION).setPackage(packageName))
        stopSelf()
    }

    override fun onDestroy() {
        isRinging = false
        stateReg?.remove()
        try { player?.release() } catch (e: Exception) { }
        try { vibrator?.cancel() } catch (e: Exception) { }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private val CHANNEL_ID = "alarm_ring"
    private val NOTIF_ID = 1001
}
