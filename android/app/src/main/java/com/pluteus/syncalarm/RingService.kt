package com.pluteus.syncalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
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
import kotlin.math.PI
import kotlin.math.sin

/**
 * 鳴動中のフォアグラウンドサービス。
 * - 音：端末のアラーム音URIに頼らず、PWA版と同じビープパターンを AudioTrack で生成してループ再生
 *   （アラーム音量ストリーム USAGE_ALARM を使用）
 * - バイブ：波形の自動リピートに頼らず、Handler で 1.6 秒ごとに打ち直して止めるまで継続
 * - Firestore の state/current を監視し、どの端末で止めても（PWA含む）即座に止まる。
 *   ただし監視の初期スナップショット（前回の ringing=false）を拾って即停止しないよう、
 *   自分の鳴動が state に反映された（ringing=true を観測した）あとでのみ停止に反応する。
 * - 10分で自動停止（PWA版と同じ）。テスト鳴動は15秒で自動停止。
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

    private var audioTrack: AudioTrack? = null
    private var mediaPlayer: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
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
    private var confirmedRinging = false

    private val vibLoop = object : Runnable {
        override fun run() {
            pulseVibration()
            handler.postDelayed(this, 1600)
        }
    }

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
        confirmedRinging = false

        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()
        if (Prefs.soundEnabled(this)) startPlayback()
        startVibrationLoop()
        openRingActivity()
        // 次回分の再スケジュール（繰り返しアラームの翌回・同期取り直し）
        SyncWorker.enqueueOneTime(this)

        val uid = Repo.uid()
        if (kind == "test") {
            // テスト鳴動は15秒で自動停止（state の test は書いた側が管理する）
            handler.postDelayed({ stopRinging() }, 16000)
            watchState()
        } else {
            // 鳴動開始を全体へ通知してから監視を始める（初期スナップショットの競合回避）
            if (uid != null) {
                Repo.writeRinging(uid, alarmId, label, timeText, Prefs.deviceName(this))
            }
            watchState()
            // 10分自動停止
            handler.postDelayed({
                val u = Repo.uid()
                if (u != null) scope.launch { Repo.writeStop(u, "（自動停止）", alarmId) }
                stopRinging()
            }, 10 * 60 * 1000L)
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

    /* ---------- 音 ---------- */

    private fun requestFocus(attrs: AudioAttributes) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { }
                    .build()
                am.requestAudioFocus(focusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        } catch (e: Exception) { }
    }

    /**
     * 曲が指定されていて端末内にファイルがあれば曲をループ再生し、
     * なければデフォルトのビープ音（生成トーン）にフォールバックする。
     */
    private fun startPlayback() {
        val name = Prefs.trackName(this)
        if (name.isNotEmpty() && Bgm.exists(this, name)) {
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                requestFocus(attrs)
                val mp = MediaPlayer()
                mp.setAudioAttributes(attrs)
                mp.setDataSource(Bgm.file(this, name).absolutePath)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                mediaPlayer = mp
                return
            } catch (e: Exception) {
                try { mediaPlayer?.release() } catch (e2: Exception) { }
                mediaPlayer = null
            }
        }
        startSound()
    }

    /* ---------- ビープ音（生成トーンを AudioTrack でループ） ---------- */

    /** PWA版と同じパターン（880Hz×2 + 1174Hz、1秒ループ）の PCM を生成する。 */
    private fun buildToneBuffer(): ShortArray {
        val sr = 44100
        val buf = ShortArray(sr) // 1秒
        fun tone(freq: Double, fromSec: Double, toSec: Double) {
            val from = (fromSec * sr).toInt()
            val to = (toSec * sr).toInt()
            for (i in from until to) {
                if (i >= buf.size) break
                val t = (i - from).toDouble() / sr
                // 立ち上がり/減衰を少し付けてクリックノイズを抑える
                val env = minOf(1.0, t / 0.01, (toSec - fromSec - t) / 0.02 + 0.2).coerceIn(0.0, 1.0)
                buf[i] = (sin(2 * PI * freq * i / sr) * 0.85 * env * Short.MAX_VALUE).toInt().toShort()
            }
        }
        tone(880.0, 0.00, 0.16)
        tone(880.0, 0.22, 0.38)
        tone(1174.0, 0.44, 0.74)
        return buf
    }

    private fun startSound() {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            // オーディオフォーカス（アラーム用途）
            requestFocus(attrs)

            val buf = buildToneBuffer()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buf.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buf, 0, buf.size)
            track.setLoopPoints(0, buf.size, -1)
            track.setVolume(1.0f)
            track.play()
            audioTrack = track
        } catch (e: Exception) { /* 音が出なくてもバイブと画面で知らせる */ }
    }

    private fun stopSound() {
        try { mediaPlayer?.stop() } catch (e: Exception) { }
        try { mediaPlayer?.release() } catch (e: Exception) { }
        mediaPlayer = null
        try { audioTrack?.stop() } catch (e: Exception) { }
        try { audioTrack?.release() } catch (e: Exception) { }
        audioTrack = null
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                am.abandonAudioFocusRequest(focusRequest!!)
            }
        } catch (e: Exception) { }
        focusRequest = null
    }

    /* ---------- バイブ（1.6秒ごとに打ち直すループ） ---------- */

    private fun startVibrationLoop() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        } catch (e: Exception) { vibrator = null }
        handler.removeCallbacks(vibLoop)
        handler.post(vibLoop)
    }

    private fun pulseVibration() {
        try {
            val v = vibrator ?: return
            val pattern = longArrayOf(0, 300, 120, 300, 120, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION") v.vibrate(pattern, -1)
            }
        } catch (e: Exception) { }
    }

    private fun stopVibration() {
        handler.removeCallbacks(vibLoop)
        try { vibrator?.cancel() } catch (e: Exception) { }
    }

    /* ---------- 状態監視 ---------- */

    /**
     * Firestore の state を監視する。
     * - 通常鳴動：自分の ringing=true が反映されたのを確認してから、ringing=false で停止する
     *   （開始直後に前回の false を拾って即停止する競合の回避）
     * - テスト鳴動：test が消えた／期限切れになったら停止する
     */
    private fun watchState() {
        val uid = Repo.uid() ?: return
        val doc = Repo.stateDoc(uid) ?: return
        stateReg?.remove()
        stateReg = doc.addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists()) return@addSnapshotListener
            if (kind == "test") {
                val test = snap.get("test") as? Map<*, *>
                val until = (test?.get("until") as? Number)?.toLong() ?: 0L
                if (test == null || until <= System.currentTimeMillis()) stopRinging()
                return@addSnapshotListener
            }
            val ringing = snap.getBoolean("ringing") ?: false
            if (ringing) {
                confirmedRinging = true
            } else if (confirmedRinging) {
                stopRinging()
            }
        }
    }

    private fun stopRinging() {
        val wasRinging = isRinging
        isRinging = false
        stopSound()
        stopVibration()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
        handler.removeCallbacksAndMessages(null)
        if (wasRinging) {
            sendBroadcast(Intent(FINISH_ACTION).setPackage(packageName))
        }
        stopSelf()
    }

    override fun onDestroy() {
        isRinging = false
        stateReg?.remove()
        stopSound()
        stopVibration()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private val CHANNEL_ID = "alarm_ring"
    private val NOTIF_ID = 1001
}
