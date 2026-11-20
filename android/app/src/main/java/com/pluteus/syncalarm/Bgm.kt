package com.pluteus.syncalarm

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/**
 * 鳴動曲（man_in_the_mirror の BGM）。
 * - 一覧：https://pluteus.github.io/man_in_the_mirror/bgm.txt（1行＝「ファイル名,作者,URL」）
 * - 実ファイル：同サイトの bgm/ フォルダ内（ファイル名は NFC 正規化＋URLエンコードして参照。
 *   本家アプリの assetPath と同じ規則）
 * - 指定された曲は端末内 files/bgm/ にダウンロードしておき、鳴動時に再生する。
 *   端末内に見つからない場合は RingService 側でデフォルトのビープ音にフォールバックする。
 */
object Bgm {
    const val LIST_URL = "https://pluteus.github.io/man_in_the_mirror/bgm.txt"
    const val BASE_URL = "https://pluteus.github.io/man_in_the_mirror/bgm/"

    data class Entry(val file: String, val author: String)

    fun dir(ctx: Context): File = File(ctx.filesDir, "bgm").apply { mkdirs() }

    fun file(ctx: Context, name: String): File = File(dir(ctx), File(name).name)

    fun exists(ctx: Context, name: String): Boolean {
        if (name.isEmpty()) return false
        val f = file(ctx, name)
        return f.exists() && f.length() > 0
    }

    fun urlFor(name: String): String =
        BASE_URL + Uri.encode(Normalizer.normalize(name, Normalizer.Form.NFC))

    /** 曲リストを取得する。失敗したら空リスト。 */
    suspend fun fetchList(): List<Entry> = withContext(Dispatchers.IO) {
        try {
            val conn = URL(LIST_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
                val p = line.split(",")
                Entry(p.getOrNull(0)?.trim() ?: "", p.getOrNull(1)?.trim() ?: "")
            }.filter { it.file.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 曲ファイルを端末内にダウンロードする（既にあれば何もしない）。成功で true。 */
    suspend fun ensureDownloaded(ctx: Context, name: String): Boolean = withContext(Dispatchers.IO) {
        if (name.isEmpty()) return@withContext false
        if (exists(ctx, name)) return@withContext true
        val tmp = File(dir(ctx), File(name).name + ".tmp")
        try {
            val conn = URL(urlFor(name)).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() > 0) {
                tmp.renameTo(file(ctx, name))
                exists(ctx, name)
            } else {
                tmp.delete()
                false
            }
        } catch (e: Exception) {
            try { tmp.delete() } catch (e2: Exception) { }
            false
        }
    }
}
