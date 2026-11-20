package com.pluteus.syncalarm

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Firebase へのアクセス集約。データ構造は PWA版と完全互換：
 * users/{uid}/alarms/{id}, users/{uid}/state/current, users/{uid}/devices/{deviceId}
 * google-services.json 未配置の環境では例外になるため、入口で null を返す。
 */
object Repo {
    fun auth(): FirebaseAuth? = try { FirebaseAuth.getInstance() } catch (e: Exception) { null }
    fun db(): FirebaseFirestore? = try { FirebaseFirestore.getInstance() } catch (e: Exception) { null }
    fun uid(): String? = auth()?.currentUser?.uid

    fun alarmsCol(uid: String): CollectionReference? =
        db()?.collection("users")?.document(uid)?.collection("alarms")

    fun timersCol(uid: String): CollectionReference? =
        db()?.collection("users")?.document(uid)?.collection("timers")

    fun stateDoc(uid: String): DocumentReference? =
        db()?.collection("users")?.document(uid)?.collection("state")?.document("current")

    fun devicesCol(uid: String): CollectionReference? =
        db()?.collection("users")?.document(uid)?.collection("devices")

    /** 全端末で同期する設定（users/{uid}/settings/app）。PWA版と共通。 */
    fun settingsDoc(uid: String): DocumentReference? =
        db()?.collection("users")?.document(uid)?.collection("settings")?.document("app")

    suspend fun writeSoundEnabled(uid: String, on: Boolean, by: String) {
        settingsDoc(uid)?.set(
            mapOf(
                "soundEnabled" to on,
                "soundUpdatedBy" to by,
                "soundUpdatedAt" to FieldValue.serverTimestamp()
            ),
            com.google.firebase.firestore.SetOptions.merge()
        )?.await()
    }

    suspend fun fetchSoundEnabled(uid: String): Boolean? {
        val d = settingsDoc(uid)?.get()?.await() ?: return null
        return if (d.exists() && d.contains("soundEnabled")) d.getBoolean("soundEnabled") else null
    }

    suspend fun writeTrackName(uid: String, name: String, by: String) {
        settingsDoc(uid)?.set(
            mapOf(
                "trackName" to name,
                "trackUpdatedBy" to by,
                "trackUpdatedAt" to FieldValue.serverTimestamp()
            ),
            com.google.firebase.firestore.SetOptions.merge()
        )?.await()
    }

    suspend fun fetchTrackName(uid: String): String? {
        val d = settingsDoc(uid)?.get()?.await() ?: return null
        return if (d.exists() && d.contains("trackName")) d.getString("trackName") else null
    }

    suspend fun writeTimeServer(uid: String, server: String, by: String) {
        settingsDoc(uid)?.set(
            mapOf(
                "timeServer" to server,
                "timeUpdatedBy" to by,
                "timeUpdatedAt" to FieldValue.serverTimestamp()
            ),
            com.google.firebase.firestore.SetOptions.merge()
        )?.await()
    }

    /** 時刻の一斉同期リクエスト（nonce が変わることで全端末が補正し直す）。 */
    suspend fun writeSyncRequest(uid: String, server: String, by: String, nonce: Long) {
        settingsDoc(uid)?.set(
            mapOf(
                "timeServer" to server,
                "syncRequestedAt" to nonce,
                "syncRequestedBy" to by
            ),
            com.google.firebase.firestore.SetOptions.merge()
        )?.await()
    }

    suspend fun fetchTimeServer(uid: String): String? {
        val d = settingsDoc(uid)?.get()?.await() ?: return null
        return if (d.exists() && d.contains("timeServer")) d.getString("timeServer") else null
    }

    suspend fun fetchAlarms(uid: String): List<AlarmItem> {
        val col = alarmsCol(uid) ?: return emptyList()
        val snap = col.get().await()
        return snap.documents.map { AlarmItem.fromDoc(it) }
            .sortedBy { it.time }
    }

    suspend fun fetchTimers(uid: String): List<TimerItem> {
        val col = timersCol(uid) ?: return emptyList()
        val snap = col.get().await()
        return snap.documents.map { TimerItem.fromDoc(it) }
            .sortedBy { it.endsAtMs }
    }

    /** プレゼンス（接続中の端末）を更新する。 */
    fun heartbeat(ctx: Context) {
        val uid = uid() ?: return
        val col = devicesCol(uid) ?: return
        col.document(Prefs.deviceId(ctx)).set(
            mapOf(
                "name" to Prefs.deviceName(ctx),
                "lastSeen" to FieldValue.serverTimestamp()
            ),
            com.google.firebase.firestore.SetOptions.merge()
        )
    }

    /** 鳴動開始を全体へ知らせる（PWA版 writeState と同じ形）。 */
    fun writeRinging(uid: String, alarmId: String?, label: String, timeText: String, startedBy: String) {
        stateDoc(uid)?.set(
            mapOf(
                "ringing" to true,
                "alarmId" to alarmId,
                "label" to label,
                "timeText" to timeText,
                "startedBy" to startedBy,
                "snoozeUntil" to null,
                "test" to null,
                "startedAtMs" to System.currentTimeMillis(),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            com.google.firebase.firestore.SetOptions.merge()
        )
    }

    /** 停止を全体へ知らせる。一回のみアラームは自動でOFFにする（PWA版 doStop と同じ）。 */
    suspend fun writeStop(uid: String, stoppedBy: String, alarmId: String?) {
        stateDoc(uid)?.set(
            mapOf(
                "ringing" to false,
                "stoppedBy" to stoppedBy,
                "snoozeUntil" to null,
                "test" to null,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            com.google.firebase.firestore.SetOptions.merge()
        )?.await()
        if (alarmId != null) {
            try {
                val doc = alarmsCol(uid)?.document(alarmId)?.get()?.await()
                if (doc != null && doc.exists()) {
                    val days = doc.get("days") as? List<*>
                    if (days.isNullOrEmpty()) {
                        alarmsCol(uid)?.document(alarmId)?.update("enabled", false)?.await()
                    }
                }
            } catch (e: Exception) { /* OFF化に失敗しても停止自体は成立させる */ }
            try {
                // タイマーは止めた端末が削除する（鳴り終わったら役目を終える）
                val tdoc = timersCol(uid)?.document(alarmId)?.get()?.await()
                if (tdoc != null && tdoc.exists()) {
                    timersCol(uid)?.document(alarmId)?.delete()?.await()
                }
            } catch (e: Exception) { /* 削除に失敗しても停止自体は成立させる */ }
        }
    }

    /** スヌーズ（5分）を全体へ知らせる。untilMs は補正済み時刻基準で呼び出し側が決める。 */
    suspend fun writeSnooze(uid: String, snoozedBy: String, untilMs: Long) {
        stateDoc(uid)?.set(
            mapOf(
                "ringing" to false,
                "snoozeUntil" to untilMs,
                "snoozedBy" to snoozedBy,
                "test" to null,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            com.google.firebase.firestore.SetOptions.merge()
        )?.await()
    }
}
