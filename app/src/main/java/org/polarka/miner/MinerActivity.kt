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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The screen of the miner: where the work comes from (a pool, a node or a wallet server), which address gets
 * paid, how many threads, how warm the battery may get. Start and stop. What is typed here stays in the app's
 * private storage on this phone.
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
    /**
     * The name and the password typed for a pool and those typed for a node or a wallet server are kept apart, and
     * the two fields show the ones of the kind of address typed above: an RPC password left in a field never goes
     * to a pool, over a connection that is not encrypted (review of 0.3.0, M2).
     */
    private val kept = HashMap<String, String>()
    private var inPool = false
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
            text = tr("Polarka Miner (test build ${version()}). It mines NORTH in a pool, or alone against your node or a wallet server, to the address you enter. ", "Polarka Miner (zkušební verze ${version()}). Těží NORTH v poolu, nebo sám proti tvému uzlu či serveru peněženek, na adresu, kterou zadáš. ") +
                tr("There are no keys to coins in this app. Mining heats the phone and shortens the life of its battery.", "Žádné klíče k mincím tu nejsou. Těžba telefon zahřívá a baterii zkracuje život.")
        })
        column.addView(label(tr("Where the work comes from. A pool: stratum+tcp://host:port, for example stratum+tcp://pool.polarka.org:3333; a pool that speaks stratum the way IronMiner does and names the RandomX key with its block, and the pool pays for shares by its own rules. stratum+tcp is not encrypted: on a network you do not trust, anyone on the way can read and change what goes to the pool, even the address that gets paid, so mine in a pool on a network you trust. Alone: your node's RPC, for example http://100.x.y.z:9554, or a wallet server that hands out work (Northbound), for example https://api.northboundwallet.org; a block found alone pays its whole reward, but on a phone that can take weeks. Over http the name and the password travel unencrypted: use http only inside a private network (Tailscale, home network).",
            "Odkud je práce. Pool: stratum+tcp://host:port, například stratum+tcp://pool.polarka.org:3333; pool, který mluví stratum jako IronMiner a klíč RandomX uvádí s jeho blokem, a za podíly platí podle svých pravidel. stratum+tcp není šifrované: v síti, které nevěříš, může kdokoli po cestě číst a měnit, co jde do poolu, i adresu, které se platí; v poolu proto těž v síti, které věříš. Sám: RPC tvého uzlu, například http://100.x.y.z:9554, nebo server peněženek, který rozdává práci (Northbound), například https://api.northboundwallet.org; blok nalezený sám vyplatí celou odměnu, ale na telefonu to může trvat týdny. Přes http jdou jméno a heslo nešifrovaně: http používej jen uvnitř soukromé sítě (Tailscale, domácí síť).")))
        url = field("stratum+tcp://… / http://…", p.getString("url", "") ?: "", plain or InputType.TYPE_TEXT_VARIATION_URI); column.addView(url)
        column.addView(label(tr("In a pool: a worker name if you like (it is put after the address) and the pool's password, empty for x. For your own node: the RPC user and password (a user limited to getblocktemplate, getblockhash, submitblock). For a wallet server leave both empty: it asks for no name. What is typed for a pool and what is typed for a node are kept apart; the fields show those of the address above.",
            "V poolu: jméno pracovníka, chceš-li (přidá se za adresu), a heslo poolu, prázdné znamená x. Pro vlastní uzel: uživatel RPC a heslo (uživatel omezený na getblocktemplate, getblockhash, submitblock). U serveru peněženek nech obě pole prázdná: jméno nechce. Co zadáš pro pool a co pro uzel, se ukládá zvlášť; pole ukazují to, co patří k adrese nahoře.")))
        for (k in listOf("user", "password", "poolUser", "poolPassword")) kept[k] = p.getString(k, "") ?: ""
        inPool = StratumUrl.isStratum(url.text.toString())
        user = field("", kept[if (inPool) "poolUser" else "user"] ?: "", plain); column.addView(user)
        password = field("", kept[if (inPool) "poolPassword" else "password"] ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD); column.addView(password)
        hints()
        url.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val now = StratumUrl.isStratum(s?.toString() ?: "")
                if (now == inPool) return
                keep()
                inPool = now
                user.setText(kept[if (inPool) "poolUser" else "user"] ?: "")
                password.setText(kept[if (inPool) "poolPassword" else "password"] ?: "")
                hints()
            }
        })
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

    /** What the two fields hold now goes to the kind of address they were shown for. */
    private fun keep() {
        kept[if (inPool) "poolUser" else "user"] = user.text.toString()
        kept[if (inPool) "poolPassword" else "password"] = password.text.toString()
    }

    private fun hints() {
        user.hint = if (inPool) tr("worker name (may stay empty)", "jméno pracovníka (může zůstat prázdné)") else tr("RPC user (empty for a wallet server)", "uživatel RPC (prázdný u serveru peněženek)")
        password.hint = if (inPool) tr("pool password (empty: x)", "heslo poolu (prázdné: x)") else tr("RPC password", "heslo RPC")
    }

    private fun toggle() {
        if (MinerState.running) {
            stopService(Intent(this, MinerService::class.java))
            return
        }
        keep()
        getSharedPreferences("miner", Context.MODE_PRIVATE).edit()
            .putString("url", url.text.toString().trim())
            .putString("user", kept["user"] ?: "")
            .putString("password", kept["password"] ?: "")
            .putString("poolUser", kept["poolUser"] ?: "")
            .putString("poolPassword", kept["poolPassword"] ?: "")
            .putString("address", address.text.toString().trim())
            .putInt("threads", threads.text.toString().toIntOrNull() ?: 2)
            .putInt("limit", limit.text.toString().toIntOrNull() ?: 40)
            .putBoolean("charging", charging.isChecked)
            .apply()
        startForegroundService(Intent(this, MinerService::class.java))
    }

    private fun show() {
        button.text = if (MinerState.running) tr("Stop mining", "Zastavit těžbu") else tr("Start mining", "Začít těžit")
        status.text = MinerState.line + if (MinerState.pool) tr("\nshares accepted: ${MinerState.accepted}, refused: ${MinerState.refused}", "\npřijaté podíly: ${MinerState.accepted}, nepřijaté: ${MinerState.refused}")
            else tr("\nblocks accepted: ${MinerState.accepted}, refused: ${MinerState.refused}", "\npřijaté bloky: ${MinerState.accepted}, nepřijaté: ${MinerState.refused}")
        val text = MinerState.log()
        val tail = if (text.length > 6000) text.substring(text.length - 6000) else text
        if (log.text.toString() != tail) log.text = tail
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
}
