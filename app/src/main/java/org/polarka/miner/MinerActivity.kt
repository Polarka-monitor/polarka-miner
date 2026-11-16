package org.polarka.miner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The screen of the miner: where the node is, which address gets the reward, how many threads, how warm the
 * battery may get. Start and stop. What is typed here stays in the app's private storage on this phone.
 * The app holds no keys to coins: it knows only the address it mines to.
 */
class MinerActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var address: EditText
    private lateinit var threads: EditText
    private lateinit var limit: EditText
    private lateinit var charging: CheckBox
    private lateinit var button: Button
    private lateinit var status: TextView
    private lateinit var log: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { show(); handler.postDelayed(this, 1000) }
    }

    private fun field(hint: String, value: String, type: Int): EditText = EditText(this).apply {
        this.hint = hint
        isSingleLine = true
        inputType = type
        setText(value)
        textSize = 14f
    }

    private fun label(text: String): TextView = TextView(this).apply { this.text = text; textSize = 12f }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val p = getSharedPreferences("miner", Context.MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 3, pad, pad) }
        val plain = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        column.addView(TextView(this).apply {
            textSize = 15f
            text = tr("Polarka Miner (test build ${version()}). It mines NORTH against your node, to the address you enter. ", "Polarka Miner (zkušební verze ${version()}). Těží NORTH proti tvému uzlu na adresu, kterou zadáš. ") +
                tr("There are no keys to coins in this app. Mining heats the phone and shortens the life of its battery.", "Žádné klíče k mincím tu nejsou. Těžba telefon zahřívá a baterii zkracuje život.")
        })
        column.addView(label(tr("Node address (RPC), for example http://100.x.y.z:9554. Over http the name and the password travel unencrypted: use an address inside a private network (Tailscale, home network).", "Adresa uzlu (RPC), například http://100.x.y.z:9554. Přes http jdou jméno a heslo nešifrovaně: použij adresu uvnitř soukromé sítě (Tailscale, domácí síť).")))
        url = field("http://…", p.getString("url", "") ?: "", plain or InputType.TYPE_TEXT_VARIATION_URI); column.addView(url)
        column.addView(label(tr("RPC user and password (a user limited to getblocktemplate, getblockhash, submitblock)", "Uživatel RPC a heslo (uživatel omezený na getblocktemplate, getblockhash, submitblock)")))
        user = field(tr("user", "uživatel"), p.getString("user", "") ?: "", plain); column.addView(user)
        password = field(tr("password", "heslo"), p.getString("password", "") ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD); column.addView(password)
        column.addView(label(tr("Address for the reward (north1z…)", "Adresa pro odměnu (north1z…)")))
        address = field("north1z…", p.getString("address", "") ?: "", plain); column.addView(address)
        column.addView(label(tr("Threads (1 to ${Runtime.getRuntime().availableProcessors()}) and the battery temperature limit in °C (30 to 45)", "Počet vláken (1 až ${Runtime.getRuntime().availableProcessors()}) a strop teploty baterie ve °C (30 až 45)")))
        threads = field("2", p.getInt("threads", 2).toString(), InputType.TYPE_CLASS_NUMBER); column.addView(threads)
        limit = field("40", p.getInt("limit", 40).toString(), InputType.TYPE_CLASS_NUMBER); column.addView(limit)
        charging = CheckBox(this).apply { text = tr("Mine only while charging", "Těžit jen při nabíjení"); isChecked = p.getBoolean("charging", true) }; column.addView(charging)
        button = Button(this).apply { isAllCaps = false; setOnClickListener { toggle() } }; column.addView(button)
        column.addView(Button(this).apply {
            isAllCaps = false
            text = tr("Benchmark (no mining)", "Měření výkonu (bez těžby)")
            setOnClickListener { if (!MinerState.running) startActivity(Intent(this@MinerActivity, MainActivity::class.java)) }
        })
        status = TextView(this).apply { textSize = 14f; setTypeface(Typeface.DEFAULT, Typeface.BOLD) }; column.addView(status)
        log = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }; column.addView(log)
        setContentView(ScrollView(this).apply { addView(column) })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun version(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    private fun toggle() {
        if (MinerState.running) {
            stopService(Intent(this, MinerService::class.java))
            return
        }
        getSharedPreferences("miner", Context.MODE_PRIVATE).edit()
            .putString("url", url.text.toString().trim())
            .putString("user", user.text.toString())
            .putString("password", password.text.toString())
            .putString("address", address.text.toString().trim())
            .putInt("threads", threads.text.toString().toIntOrNull() ?: 2)
            .putInt("limit", limit.text.toString().toIntOrNull() ?: 40)
            .putBoolean("charging", charging.isChecked)
            .apply()
        startForegroundService(Intent(this, MinerService::class.java))
    }

    private fun show() {
        button.text = if (MinerState.running) tr("Stop mining", "Zastavit těžbu") else tr("Start mining", "Začít těžit")
        status.text = MinerState.line + tr("\nblocks accepted: ${MinerState.accepted}, refused: ${MinerState.refused}", "\npřijaté bloky: ${MinerState.accepted}, nepřijaté: ${MinerState.refused}")
        val text = MinerState.log()
        val tail = if (text.length > 6000) text.substring(text.length - 6000) else text
        if (log.text.toString() != tail) log.text = tail
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
}
