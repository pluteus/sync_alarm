package com.pluteus.syncalarm

import com.google.firebase.firestore.DocumentSnapshot
import java.util.Calendar

/**
 * アラーム1件。Firestore の構造は PWA版と同一：
 * users/{uid}/alarms/{id} = { time:"07:00", label:"", days:[1,2,...], enabled:true }
 * days は JS の getDay() と同じ 0=日,1=月,...,6=土。空なら一回のみ。
 */
data class AlarmItem(
    val id: String,
    val time: String,
    val label: String,
    val days: List<Int>,
    val enabled: Boolean
) {
    fun nextFire(fromMs: Long): Long? = nextFireMillis(time, days, fromMs)

    companion object {
        fun fromDoc(d: DocumentSnapshot): AlarmItem {
            val daysRaw = d.get("days") as? List<*> ?: emptyList<Any>()
            return AlarmItem(
                id = d.id,
                time = d.getString("time") ?: "07:00",
                label = d.getString("label") ?: "",
                days = daysRaw.mapNotNull { (it as? Number)?.toInt() },
                enabled = d.getBoolean("enabled") ?: true
            )
        }

        /** PWA版 nextFireOf と同じ計算：fromMs より後で最初に鳴る時刻を返す。 */
        fun nextFireMillis(time: String, days: List<Int>, fromMs: Long): Long? {
            val parts = time.split(":")
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            for (add in 0..7) {
                val c = Calendar.getInstance().apply {
                    timeInMillis = fromMs
                    add(Calendar.DAY_OF_MONTH, add)
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (c.timeInMillis <= fromMs) continue
                val dow = c.get(Calendar.DAY_OF_WEEK) - 1 // 0=日 … PWAのgetDay()と一致
                if (days.isNotEmpty() && !days.contains(dow)) continue
                return c.timeInMillis
            }
            return null
        }
    }
}
