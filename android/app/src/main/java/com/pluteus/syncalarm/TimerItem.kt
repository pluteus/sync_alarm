package com.pluteus.syncalarm

import com.google.firebase.firestore.DocumentSnapshot

/**
 * タイマー1件。Firestore の構造は PWA版と同一：
 * users/{uid}/timers/{id} = { label, hours, minutes, durationMs, endsAtMs, createdBy }
 * endsAtMs（終了予定の絶対時刻）を全端末で共有し、各端末が残り時間を数える。
 */
data class TimerItem(
    val id: String,
    val label: String,
    val durationMs: Long,
    val endsAtMs: Long
) {
    /** 鳴動画面などに表示する設定時間（H:MM 形式）。 */
    fun durationText(): String {
        val h = durationMs / 3600000
        val m = durationMs % 3600000 / 60000
        return "%d:%02d".format(h, m)
    }

    companion object {
        fun fromDoc(d: DocumentSnapshot): TimerItem = TimerItem(
            id = d.id,
            label = d.getString("label") ?: "",
            durationMs = (d.get("durationMs") as? Number)?.toLong() ?: 0L,
            endsAtMs = (d.get("endsAtMs") as? Number)?.toLong() ?: 0L
        )
    }
}
