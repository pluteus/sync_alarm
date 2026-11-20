package com.pluteus.syncalarm

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.pluteus.syncalarm.databinding.ActivityMainBinding
import com.pluteus.syncalarm.databinding.DialogAlarmBinding
import com.pluteus.syncalarm.databinding.DialogSettingsBinding
import com.pluteus.syncalarm.databinding.ItemAlarmBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var signInClient: GoogleSignInClient? = null
    private var alarms: List<AlarmItem> = emptyList()
    private var alarmsReg: ListenerRegistration? = null
    private var stateReg: ListenerRegistration? = null
    private var settingsReg: ListenerRegistration? = null
    private var settingsSoundRefresh: (() -> Unit)? = null
    private var settingsTrackRefresh: (() -> Unit)? = null
    private var stateMap: Map<String, Any?>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var testRingActive = false

    private val dayNames = arrayOf("日", "月", "火", "水", "木", "金", "土")

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(res.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val cred = GoogleAuthProvider.getCredential(account.idToken, null)
            Repo.auth()?.signInWithCredential(cred)?.addOnCompleteListener { t ->
                if (!t.isSuccessful) {
                    binding.signInError.text = "ログインに失敗しました：${t.exception?.message ?: ""}"
                }
            }
        } catch (e: Exception) {
            binding.signInError.text = "ログインに失敗しました：${e.message ?: ""}"
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tickClock()
            handler.postDelayed(this, 250)
        }
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            Repo.heartbeat(this@MainActivity)
            handler.postDelayed(this, 20000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SyncWorker.enqueuePeriodic(this)

        binding.settingsBtn.setOnClickListener { showSettingsDialog() }
        binding.addBtn.setOnClickListener { showAlarmDialog(null) }
        binding.testBtn.setOnClickListener {
            val uid = Repo.uid() ?: return@setOnClickListener
            Repo.stateDoc(uid)?.set(
                mapOf(
                    "test" to mapOf("until" to (System.currentTimeMillis() + 15000), "by" to Prefs.deviceName(this)),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            Toast.makeText(this, "テスト鳴動を全端末に送りました（15秒）", Toast.LENGTH_SHORT).show()
        }
        binding.signInBtn.setOnClickListener {
            val client = signInClient ?: buildSignInClient()
            if (client == null) {
                binding.signInError.text = "Firebase設定（google-services.json）が未配置のためログインできません。README-ANDROID.md の手順を確認してください。"
                return@setOnClickListener
            }
            signInLauncher.launch(client.signInIntent)
        }

        val auth = Repo.auth()
        if (auth == null) {
            showSignIn("Firebase設定（google-services.json）が未配置です。README-ANDROID.md の手順で配置してください。")
            return
        }
        auth.addAuthStateListener { a ->
            val user = a.currentUser
            if (user == null) {
                showSignIn(null)
                stopMainListeners()
            } else {
                showMain()
                startMainListeners()
            }
        }
    }

    private fun buildSignInClient(): GoogleSignInClient? {
        return try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build()
            GoogleSignIn.getClient(this, gso).also { signInClient = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun showSignIn(error: String?) {
        binding.signInLayout.visibility = View.VISIBLE
        binding.mainLayout.visibility = View.GONE
        if (error != null) binding.signInError.text = error
    }

    private fun showMain() {
        binding.signInLayout.visibility = View.GONE
        binding.mainLayout.visibility = View.VISIBLE
        requestNotificationPermission()
        updatePermWarning()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        updatePermWarning()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun startMainListeners() {
        val uid = Repo.uid() ?: return
        val col = Repo.alarmsCol(uid) ?: return
        alarmsReg?.remove()
        alarmsReg = col.addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            alarms = snap.documents.map { AlarmItem.fromDoc(it) }.sortedBy { it.time }
            renderAlarms()
            AlarmScheduler.rescheduleAll(this, alarms)
            tickClock()
        }
        val doc = Repo.stateDoc(uid)
        stateReg?.remove()
        stateReg = doc?.addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists()) { stateMap = null; renderStateMsg(); return@addSnapshotListener }
            stateMap = snap.data
            renderStateMsg()
            handleTestState()
        }
        // 同期設定（音ON/OFF・鳴動曲）：他端末で切り替えたらこの端末にも反映する
        val sDoc = Repo.settingsDoc(uid)
        settingsReg?.remove()
        settingsReg = sDoc?.addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists()) return@addSnapshotListener
            val v = snap.getBoolean("soundEnabled")
            if (v != null && v != Prefs.soundEnabled(this)) {
                Prefs.setSoundEnabled(this, v)
                settingsSoundRefresh?.invoke()
            }
            val t = snap.getString("trackName")
            if (t != null && t != Prefs.trackName(this)) {
                Prefs.setTrackName(this, t)
                settingsTrackRefresh?.invoke()
                // 指定された曲はこの端末にもダウンロードしておく
                if (t.isNotEmpty()) {
                    lifecycleScope.launch { Bgm.ensureDownloaded(this@MainActivity, t) }
                }
            }
        }
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
        SyncWorker.enqueueOneTime(this)
    }

    private fun stopMainListeners() {
        alarmsReg?.remove(); alarmsReg = null
        stateReg?.remove(); stateReg = null
        settingsReg?.remove(); settingsReg = null
        handler.removeCallbacks(heartbeat)
    }

    /** PWA/他端末からのテスト鳴動：アプリを開いている間だけ追随する（PWA版と同じ制約）。 */
    private fun handleTestState() {
        val test = stateMap?.get("test") as? Map<*, *> ?: run { testRingActive = false; return }
        val until = (test["until"] as? Number)?.toLong() ?: 0
        if (until > System.currentTimeMillis() && !RingService.isRinging && !testRingActive) {
            testRingActive = true
            val i = Intent(this, RingService::class.java).apply {
                action = RingService.ACTION_RING
                putExtra(RingService.EXTRA_KIND, "test")
                putExtra(RingService.EXTRA_LABEL, "テスト鳴動")
                putExtra(RingService.EXTRA_TIME, SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date()))
            }
            ContextCompat.startForegroundService(this, i)
            handler.postDelayed({ testRingActive = false }, 16000)
        }
    }

    private fun tickClock() {
        if (binding.mainLayout.visibility != View.VISIBLE) return
        val now = System.currentTimeMillis()
        binding.clockText.text = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(now))
        binding.dateText.text = SimpleDateFormat("M月d日（E）", Locale.JAPAN).format(Date(now))
        val snoozeUntil = (stateMap?.get("snoozeUntil") as? Number)?.toLong()
        if (snoozeUntil != null && snoozeUntil > now) {
            binding.nextText.text = "スヌーズ中… ${SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(snoozeUntil))} に再び鳴ります"
            return
        }
        var best: Long? = null
        var bestAlarm: AlarmItem? = null
        for (a in alarms) {
            if (!a.enabled) continue
            val t = a.nextFire(now) ?: continue
            if (best == null || t < best!!) { best = t; bestAlarm = a }
        }
        if (best != null) {
            val diff = best!! - now
            val hh = diff / 3600000
            val mm = diff % 3600000 / 60000
            val ss = diff % 60000 / 1000
            val labelPart = bestAlarm?.label?.takeIf { it.isNotEmpty() }?.let { "「$it」" } ?: ""
            binding.nextText.text = "次のアラーム ${SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(best!!))}（あと ${if (hh > 0) "${hh}時間" else ""}${mm}分${ss}秒）$labelPart"
        } else {
            binding.nextText.text = "次のアラームはありません"
        }
    }

    private fun renderStateMsg() {
        val s = stateMap
        val text = when {
            s == null -> ""
            s["ringing"] == true -> "🔔 鳴動中です" + ((s["startedBy"] as? String)?.let { "（$it）" } ?: "")
            ((s["snoozeUntil"] as? Number)?.toLong() ?: 0) > System.currentTimeMillis() ->
                "💤 スヌーズ中（${s["snoozedBy"] as? String ?: ""}）"
            s["stoppedBy"] != null -> "✅ 前回のアラームは ${s["stoppedBy"]} が止めました"
            else -> ""
        }
        binding.stateMsgText.text = text
        binding.stateMsgText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderAlarms() {
        val container = binding.alarmsContainer
        container.removeAllViews()
        if (alarms.isEmpty()) {
            val tv = TextView(this).apply {
                text = "アラームはまだありません。「＋ アラームを追加」から追加してください。"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                textSize = 12.5f
            }
            container.addView(tv)
            return
        }
        for (a in alarms) {
            val row = ItemAlarmBinding.inflate(LayoutInflater.from(this), container, false)
            row.timeText.text = a.time
            row.labelText.text = a.label.ifEmpty { "（ラベルなし）" }
            row.daysText.text = if (a.days.isNotEmpty())
                a.days.sorted().joinToString(" ") { dayNames[it] } + " くり返し"
            else "一回のみ"
            row.enabledSwitch.isChecked = a.enabled
            row.enabledSwitch.setOnCheckedChangeListener { _, checked ->
                val uid = Repo.uid() ?: return@setOnCheckedChangeListener
                Repo.alarmsCol(uid)?.document(a.id)?.update("enabled", checked)
            }
            row.editBtn.setOnClickListener { showAlarmDialog(a) }
            row.deleteBtn.setOnClickListener {
                AlertDialog.Builder(this)
                    .setMessage("このアラームを削除しますか？")
                    .setPositiveButton("削除") { _, _ ->
                        val uid = Repo.uid() ?: return@setPositiveButton
                        Repo.alarmsCol(uid)?.document(a.id)?.delete()
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
            container.addView(row.root)
        }
    }

    /** アラーム追加/編集ダイアログ（PWA版モーダルと同項目：時刻・ラベル・繰り返し）。 */
    private fun showAlarmDialog(existing: AlarmItem?) {
        val uid = Repo.uid() ?: return
        val dlg = DialogAlarmBinding.inflate(LayoutInflater.from(this))
        val dayBoxes: List<Pair<CheckBox, Int>> = listOf(
            dlg.cbMon to 1, dlg.cbTue to 2, dlg.cbWed to 3, dlg.cbThu to 4,
            dlg.cbFri to 5, dlg.cbSat to 6, dlg.cbSun to 0
        )
        if (existing != null) {
            val (h, m) = existing.time.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 7) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
            dlg.timePicker.hour = h
            dlg.timePicker.minute = m
            dlg.labelInput.setText(existing.label)
            dayBoxes.forEach { (box, v) -> box.isChecked = existing.days.contains(v) }
        } else {
            dlg.timePicker.hour = 7
            dlg.timePicker.minute = 0
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing != null) "アラームを編集" else "アラームを追加")
            .setView(dlg.root)
            .setPositiveButton(if (existing != null) "保存する" else "追加する") { _, _ ->
                val time = "%02d:%02d".format(dlg.timePicker.hour, dlg.timePicker.minute)
                val label = dlg.labelInput.text.toString().trim()
                val days = dayBoxes.filter { it.first.isChecked }.map { it.second }
                val col = Repo.alarmsCol(uid) ?: return@setPositiveButton
                if (existing != null) {
                    // 編集したら必ず有効化する
                    col.document(existing.id).update(mapOf("time" to time, "label" to label, "days" to days, "enabled" to true))
                } else {
                    col.add(
                        mapOf(
                            "time" to time, "label" to label, "days" to days,
                            "enabled" to true, "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** 設定ダイアログ（PWA版の設定モーダルと同項目＋Android固有の権限案内）。 */
    private fun showSettingsDialog() {
        val dlg = DialogSettingsBinding.inflate(LayoutInflater.from(this))
        dlg.deviceNameInput.setText(Prefs.deviceName(this))
        dlg.saveDeviceBtn.setOnClickListener {
            Prefs.setDeviceName(this, dlg.deviceNameInput.text.toString().trim().ifEmpty { Build.MODEL ?: "Android" })
            Repo.heartbeat(this)
            Toast.makeText(this, "端末名を保存しました", Toast.LENGTH_SHORT).show()
        }
        fun refreshSound() {
            dlg.soundBtn.text = if (Prefs.soundEnabled(this)) "🔊 音：ON（同期）" else "🔇 音：OFF（同期）"
        }
        refreshSound()
        settingsSoundRefresh = { refreshSound() }
        dlg.soundBtn.setOnClickListener {
            val next = !Prefs.soundEnabled(this)
            Prefs.setSoundEnabled(this, next)
            refreshSound()
            // 全端末へ同期する
            val u = Repo.uid()
            if (u != null) {
                lifecycleScope.launch { Repo.writeSoundEnabled(u, next, Prefs.deviceName(this@MainActivity)) }
            }
        }
        fun refreshTrack() {
            val t = Prefs.trackName(this)
            dlg.trackBtn.text = if (t.isEmpty()) "🎵 曲：なし（ビープ音）" else "🎵 曲：" + t.removeSuffix(".mp3")
            dlg.trackHint.text = when {
                t.isEmpty() -> "曲を指定すると各端末にダウンロードされ、鳴動時に再生されます。見つからない場合はビープ音になります。"
                Bgm.exists(this, t) -> "「$t」はこの端末にダウンロード済みです。"
                else -> "「$t」はまだこの端末にありません。鳴動時はビープ音になります（アプリを開いた時／定期同期で自動ダウンロードします）。"
            }
        }
        refreshTrack()
        settingsTrackRefresh = { refreshTrack() }
        dlg.trackBtn.setOnClickListener {
            lifecycleScope.launch {
                val entries = Bgm.fetchList()
                if (entries.isEmpty()) {
                    Toast.makeText(this@MainActivity, "曲リストを取得できませんでした", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val labels = (listOf("なし（ビープ音）") + entries.map { e ->
                    e.file.removeSuffix(".mp3") + if (e.author.isNotEmpty()) "（${e.author}）" else ""
                }).toTypedArray()
                val cur = Prefs.trackName(this@MainActivity)
                val checked = if (cur.isEmpty()) 0 else entries.indexOfFirst { it.file == cur } + 1
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("🎵 鳴動曲を選ぶ")
                    .setSingleChoiceItems(labels, checked) { d, which ->
                        val name = if (which == 0) "" else entries[which - 1].file
                        Prefs.setTrackName(this@MainActivity, name)
                        refreshTrack()
                        // 全端末へ同期する（各端末は受け取った時点で曲をダウンロードする）
                        val u = Repo.uid()
                        if (u != null) {
                            lifecycleScope.launch { Repo.writeTrackName(u, name, Prefs.deviceName(this@MainActivity)) }
                        }
                        if (name.isNotEmpty()) {
                            lifecycleScope.launch {
                                val ok = Bgm.ensureDownloaded(this@MainActivity, name)
                                Toast.makeText(
                                    this@MainActivity,
                                    if (ok) "曲をダウンロードしました" else "ダウンロードに失敗しました。鳴動時はビープ音になります",
                                    Toast.LENGTH_LONG
                                ).show()
                                refreshTrack()
                            }
                        }
                        d.dismiss()
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
        }
        dlg.batteryBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        fun refreshExact() {
            val am = getSystemService(AlarmManager::class.java)
            val ok = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am?.canScheduleExactAlarms() == true
            dlg.exactBtn.text = if (ok) "正確なアラーム：許可済み" else "正確なアラーム：許可する"
            dlg.exactBtn.isEnabled = !ok
        }
        refreshExact()
        dlg.exactBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
            }
        }
        val uid = Repo.uid()
        dlg.accountText.text = "ログイン中：" + (Repo.auth()?.currentUser?.email ?: "")
        dlg.devicesText.text = "読み込み中…"
        if (uid != null) {
            lifecycleScope.launch {
                try {
                    val snap = Repo.devicesCol(uid)!!.get().await()
                    val now = System.currentTimeMillis()
                    val fmt = SimpleDateFormat("HH:mm", Locale.JAPAN)
                    val lines = snap.documents.map { d ->
                        val name = d.getString("name") ?: "（名前なし）"
                        val last = d.getTimestamp("lastSeen")?.toDate()?.time ?: 0L
                        val online = last > 0 && now - last < 75000
                        val mine = if (d.id == Prefs.deviceId(this@MainActivity)) "（この端末）" else ""
                        (if (online) "🟢 " else "⚪ ") + name + mine +
                            (if (online) " 接続中" else if (last > 0) " 最終確認 " + fmt.format(Date(last)) else "")
                    }
                    dlg.devicesText.text = if (lines.isEmpty()) "端末情報はまだありません" else lines.joinToString("\n")
                } catch (e: Exception) {
                    dlg.devicesText.text = "端末一覧を取得できませんでした"
                }
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("⚙️ 設定")
            .setView(dlg.root)
            .setNegativeButton("閉じる", null)
            .create()
        dialog.setOnDismissListener { settingsSoundRefresh = null; settingsTrackRefresh = null }
        dlg.logoutBtn.setOnClickListener {
            Repo.auth()?.signOut()
            try { signInClient?.signOut() } catch (e: Exception) { }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun updatePermWarning() {
        if (binding.mainLayout.visibility != View.VISIBLE) return
        val am = getSystemService(AlarmManager::class.java)
        val exactNg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am?.canScheduleExactAlarms() != true
        val pm = getSystemService(PowerManager::class.java)
        val battNg = pm?.isIgnoringBatteryOptimizations(packageName) != true
        binding.permWarnText.visibility = if (exactNg || battNg) View.VISIBLE else View.GONE
        if (exactNg || battNg) {
            binding.permWarnText.text = "⚠️ 確実に鳴らすため、⚙️設定から「正確なアラームの許可」と「電池の最適化から除外」を確認してください。"
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }
}
