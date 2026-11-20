package com.pluteus.syncalarm

import android.content.Context
import android.os.Build

/** 端末ローカルの設定（PWA版の localStorage 相当）。 */
object Prefs {
    private const val FILE = "syncalarm_prefs"
    private const val KEY_DEV_ID = "deviceId"
    private const val KEY_DEV_NAME = "deviceName"
    private const val KEY_SOUND = "soundEnabled"
    private const val KEY_TRACK = "trackName"
    private const val KEY_TIME_SERVER = "timeServer"
    private const val KEY_TIME_OFFSET = "timeOffsetMs"
    private const val KEY_TIME_STATUS = "timeSyncStatus"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun deviceId(ctx: Context): String {
        val s = sp(ctx)
        var id = s.getString(KEY_DEV_ID, null)
        if (id == null) {
            id = "dev-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
            s.edit().putString(KEY_DEV_ID, id).apply()
        }
        return id
    }

    fun deviceName(ctx: Context): String =
        sp(ctx).getString(KEY_DEV_NAME, null) ?: (Build.MODEL ?: "Android")

    fun setDeviceName(ctx: Context, name: String) {
        sp(ctx).edit().putString(KEY_DEV_NAME, name).apply()
    }

    /** 音ON/OFF（デフォルトON）。バイブレーションはこの設定に関わらず鳴動時に作動する（PWA版と同じ仕様）。 */
    fun soundEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SOUND, true)

    fun setSoundEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_SOUND, on).apply()
    }

    /** 鳴動曲のファイル名（全端末で同期する設定の端末側キャッシュ）。空＝ビープ音。 */
    fun trackName(ctx: Context): String = sp(ctx).getString(KEY_TRACK, null) ?: ""

    fun setTrackName(ctx: Context, name: String) {
        sp(ctx).edit().putString(KEY_TRACK, name).apply()
    }

    /** 時刻サーバー（全端末で同期する設定の端末側キャッシュ）。 */
    fun timeServer(ctx: Context): String =
        sp(ctx).getString(KEY_TIME_SERVER, null) ?: TimeSync.DEFAULT_SERVER

    fun setTimeServer(ctx: Context, server: String) {
        sp(ctx).edit().putString(KEY_TIME_SERVER, server).apply()
    }

    /** 時刻のずれ（正しい時刻 − 端末時刻、ミリ秒）。TimeSync.calibrate が更新する。 */
    fun timeOffsetMs(ctx: Context): Long = sp(ctx).getLong(KEY_TIME_OFFSET, 0L)

    fun setTimeOffsetMs(ctx: Context, ms: Long) {
        sp(ctx).edit().putLong(KEY_TIME_OFFSET, ms).apply()
    }

    /** 最後に成功した時刻同期の表示用ステータス。 */
    fun timeSyncStatus(ctx: Context): String = sp(ctx).getString(KEY_TIME_STATUS, null) ?: ""

    fun setTimeSyncStatus(ctx: Context, s: String) {
        sp(ctx).edit().putString(KEY_TIME_STATUS, s).apply()
    }
}
