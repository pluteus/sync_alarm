package com.pluteus.syncalarm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 時刻サーバー（NTP）との時刻補正。
 * Android版は UDP で SNTP 問い合わせができるため、選択されたサーバーからずれを実測する。
 * ずれ（offsetMs ＝ 正しい時刻 − 端末時刻）を端末内に保存し、スケジュール計算は
 * 「正しい時刻」基準で組み立て、AlarmManager へ渡す直前に端末時刻へ変換する。
 * （PWA版はブラウザの制限で UDP を使えないため、Firestore のサーバー時刻で補正している）
 */
object TimeSync {
    val SERVERS = listOf(
        "ntp.nict.jp",
        "time.google.com",
        "time.asia.apple.com",
        "time.apple.com",
        "time.windows.com"
    )
    const val DEFAULT_SERVER = "ntp.nict.jp"

    /** 補正済みの現在時刻（全端末で共有する「正しい時刻」の枠）。 */
    fun now(ctx: Context): Long = System.currentTimeMillis() + Prefs.timeOffsetMs(ctx)

    /** AlarmManager へ渡す端末時刻へ変換する。 */
    fun toDeviceTime(ctx: Context, trueMs: Long): Long = trueMs - Prefs.timeOffsetMs(ctx)

    /**
     * SNTP でサーバーと同期し、ずれを保存する。
     * 成功したらステータス文字列、失敗したら null を返す。
     */
    suspend fun calibrate(ctx: Context, server: String): String? = withContext(Dispatchers.IO) {
        try {
            val offset = queryOffset(server) ?: return@withContext null
            Prefs.setTimeOffsetMs(ctx, offset)
            Prefs.setTimeServer(ctx, server)
            val sign = if (offset >= 0) "+" else ""
            val status = "$server で補正： $sign${"%.2f".format(offset / 1000.0)}秒"
            Prefs.setTimeSyncStatus(ctx, status)
            status
        } catch (e: Exception) {
            null
        }
    }

    /** SNTP（UDP 123）でサーバー時刻とのずれ（ミリ秒）を測る。 */
    private fun queryOffset(server: String): Long? {
        val address = InetAddress.getByName(server)
        val socket = DatagramSocket()
        socket.soTimeout = 4000
        try {
            val buf = ByteArray(48)
            buf[0] = 0x1B // LI=0, VN=3, Mode=3(client)
            val t0 = System.currentTimeMillis()
            socket.send(DatagramPacket(buf, buf.size, address, 123))
            val recv = DatagramPacket(ByteArray(48), 48)
            socket.receive(recv)
            val t3 = System.currentTimeMillis()
            val data = recv.data
            fun ts(offset: Int): Long {
                var secs = 0L
                for (i in 0..3) secs = (secs shl 8) or (data[offset + i].toLong() and 0xFF)
                var frac = 0L
                for (i in 0..3) frac = (frac shl 8) or (data[offset + 4 + i].toLong() and 0xFF)
                return (secs - 2208988800L) * 1000 + frac * 1000 / 4294967296L
            }
            val t1 = ts(32) // サーバー受信時刻
            val t2 = ts(40) // サーバー送信時刻
            return ((t1 - t0) + (t2 - t3)) / 2
        } finally {
            try { socket.close() } catch (e: Exception) { }
        }
    }
}
