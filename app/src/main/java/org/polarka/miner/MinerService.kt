package org.polarka.miner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the screen shows about the mining service. Written by the service, read by the activity. */
object MinerState {
    @Volatile var running = false
    @Volatile var line = tr("not running", "neběží")
    @Volatile var rate = 0.0
    @Volatile var accepted = 0
    @Volatile var refused = 0
    @Volatile var height = 0
    private val text = StringBuilder()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    fun say(s: String) {
        Log.i("PolarkaMiner", s)
        synchronized(text) {
            text.append(clock.format(Date())).append(' ').append(s).append('\n')
            if (text.length > 60_000) text.delete(0, text.length - 40_000)
        }
    }

    fun log(): String = synchronized(text) { text.toString() }
}

/** The processor's cores, fastest first, as (number, top frequency in MHz). */
fun fastestCores(): List<Pair<Int, Int>> {
    val n = Runtime.getRuntime().availableProcessors()
    return (0 until n).map { cpu ->
        val f = try { File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toInt() / 1000 } catch (e: Exception) { 0 }
        cpu to f
    }.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
}

/**
 * Mines while the app is not on the screen. Android stops an app that leaves the screen unless it runs a
 * service with a permanent notification, so this is that service: the notification says that the phone is
 * mining and how fast. It holds a wake lock, asks the node for work, hands the header to the native workers
 * and sends a block when one is found. It pauses when the battery is too warm, when the system reports
 * severe heat, and (if asked) when the phone is not charging. No keys: it knows one address to pay to.
 */
class MinerService : Service() {
    private var thread: Thread? = null
    private var templates: Thread? = null
    @Volatile private var stop = false
    @Volatile private var node: NodeClient? = null
    private var wake: PowerManager.WakeLock? = null

    private val lock = Any()
    private val jobs = LinkedHashMap<Long, Work>()
    private var jobId = 0L
    private var current: Work? = null
    private var keyHeight = -1
    private var submitted = 0L
    @Volatile private var paused: String? = null
    private var cpus = IntArray(0)
    private val submitter = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var wonOn = ""
    private var checked = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (thread != null) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Mining", "Těžba"), NotificationManager.IMPORTANCE_LOW))
        val n = notification(tr("starting", "spouštím"))
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "polarka:miner").apply { acquire() }
        stop = false
        MinerState.running = true
        thread = Thread({ run() }, "miner-control").apply { start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stop = true
        node?.interrupt()
        MinerState.line = tr("stopping", "zastavuji")
        val control = thread
        val asking = templates
        val held = wake
        // Nothing here waits on the main thread. In this order, on a thread of its own: the two loops end
        // (a dataset that is being built is finished first), blocks still on their way are sent, the workers
        // stop, the memory is given back. A run started right after waits for all of it.
        cleanup = Thread({
            try { control?.join(); asking?.join() } catch (e: InterruptedException) { }
            submitter.shutdown()
            try { submitter.awaitTermination(45, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) { }
            Native.stop()
            Native.release()
            held?.let { if (it.isHeld) it.release() }
            MinerState.running = false
            MinerState.line = tr("not running", "neběží")
            MinerState.rate = 0.0
            MinerState.say(tr("mining stopped", "těžba zastavena"))
        }, "miner-cleanup").apply { start() }
        super.onDestroy()
    }

    /** Sends a found block. A connection that fails is not the node's "no": the block is sent again. */
    private fun submit(client: NodeClient, w: Work, nonce: Long) {
        if (w.previous == wonOn) return
        val hex = Hex.encode(w.block(nonce))
        var tries = 0
        while (true) {
            tries++
            try {
                val res = client.submit(hex)
                // "duplicate" after a failed try means the first try did arrive and only its answer was lost
                if (res == null || (res == "duplicate" && tries > 1)) {
                    MinerState.accepted++
                    wonOn = w.previous
                    MinerState.say(tr("BLOCK ${w.height} ACCEPTED by the node (nonce $nonce)", "BLOK ${w.height} PŘIJAT uzlem (nonce $nonce)"))
                } else {
                    MinerState.refused++
                    MinerState.say(tr("the node refused block ${w.height}: $res", "blok ${w.height} uzel nepřijal: $res"))
                    refresh = true
                    client.interrupt()
                }
                return
            } catch (e: Exception) {
                if (tries >= 8 || (stop && tries >= 3)) {
                    MinerState.refused++
                    MinerState.say(tr("block ${w.height} could not be sent after $tries tries: ${e.message}", "blok ${w.height} se nepodařilo odeslat ani na $tries pokusů: ${e.message}"))
                    refresh = true
                    client.interrupt()
                    return
                }
                MinerState.say(tr("sending block ${w.height} failed (${e.message}), trying again", "odeslání bloku ${w.height} selhalo (${e.message}), zkouším znovu"))
                try { Thread.sleep(3000) } catch (ie: InterruptedException) { }
            }
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MinerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(tr("Polarka Miner is mining NORTH", "Polarka Miner těží NORTH"))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun battery(): Triple<Double, Boolean, Int> {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // no reading, or a reading of zero or less, is "unknown" and not "cold": the guard then pauses
        val raw = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val temp = if (raw <= 0) Double.NaN else raw / 10.0
        val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return Triple(temp, plugged, getSystemService(PowerManager::class.java).currentThermalStatus)
    }

    private fun nap(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (!stop && System.currentTimeMillis() < end) Thread.sleep(100)
    }

    private var currentId = 0L
    private var extranonce = 0L
    @Volatile private var refresh = false

    private fun run() {
        try { cleanup?.join() } catch (e: InterruptedException) { }
        val p = getSharedPreferences("miner", Context.MODE_PRIVATE)
        val url = (p.getString("url", "") ?: "").trim()
        val address = (p.getString("address", "") ?: "").trim()
        val threads = p.getInt("threads", 2).coerceIn(1, Runtime.getRuntime().availableProcessors())
        val limit = p.getInt("limit", 40).coerceIn(30, 45)
        val onlyCharging = p.getBoolean("charging", true)
        try {
            require(url.startsWith("http://") || url.startsWith("https://")) { tr("the node address must start with http:// or https://", "adresa uzlu musí začínat http:// nebo https://") }
            val (hrp, pay) = Blocks.addressScript(address)
            val client = NodeClient(url, p.getString("user", "") ?: "", p.getString("password", "") ?: "")
            node = client
            cpus = fastestCores().take(threads).map { it.first }.toIntArray()
            extranonce = SecureRandom().nextLong()
            MinerState.say(tr("starting: address ${address.take(10)}…${address.takeLast(6)} (${Blocks.PREFIXES[hrp]}), threads $threads, ", "začínám: adresa ${address.take(10)}…${address.takeLast(6)} (${Blocks.PREFIXES[hrp]}), počet vláken $threads, ") +
                tr("battery limit $limit °C, ", "strop teploty baterie $limit °C, ") + if (onlyCharging) tr("only while charging", "jen při nabíjení") else tr("also when not charging", "i bez nabíječky"))
            templates = Thread({ templateLoop(client, pay) }, "miner-templates").apply { start() }
            controlLoop(client, limit, onlyCharging, threads)
            templates?.join(3000)
        } catch (e: IllegalArgumentException) {
            MinerState.say(tr("cannot start: ${e.message}", "nelze začít: ${e.message}"))
            MinerState.line = tr("cannot start: ${e.message}", "nelze začít: ${e.message}")
            MinerState.running = false
            stopSelf()
        }
    }

    /** Keeps asking the node for work. The first question is plain, the next ones wait at the node (long poll). */
    private fun templateLoop(client: NodeClient, pay: ByteArray) {
        var lp: String? = null
        var failingSince = 0L
        var lastMessage = ""
        fun trouble(msg: String) {
            val now = System.currentTimeMillis()
            if (msg != lastMessage) { MinerState.say(msg); lastMessage = msg }
            lp = null
            if (failingSince == 0L) failingSince = now
            if (now - failingSince > 90_000) {
                synchronized(lock) { current = null; Native.setJob(null, null, 0) }
                MinerState.line = tr("waiting for the node", "čekám na uzel") + ": $msg"
            }
            nap(5000)
        }
        while (!stop) {
            if (refresh) { refresh = false; lp = null }
            try {
                val w = newWork(client, client.template(lp), pay)
                lp = w.longPollId
                failingSince = 0L
                lastMessage = ""
            } catch (e: java.net.SocketTimeoutException) {
                // a long poll that ran out of time is normal: ask again; a plain question that did is not
                if (lp == null) trouble(tr("the node does not answer in time", "uzel neodpovídá včas")) else lp = null
            } catch (e: NodeError) {
                trouble(e.message ?: tr("the node reports an error", "uzel hlásí chybu"))
            } catch (e: IOException) {
                if (stop) break
                if (refresh) continue
                trouble(tr("could not reach the node: ${e.message}", "spojení s uzlem se nepodařilo: ${e.message}"))
            } catch (e: Exception) {
                trouble(tr("the block template cannot be used: ${e.message}", "šablonu bloku nelze použít: ${e.message}"))
            }
        }
    }

    private var lastWorkSaid = 0L

    private fun newWork(client: NodeClient, t: org.json.JSONObject, pay: ByteArray): Work {
        val w = Blocks.assemble(t, pay, synchronized(lock) { extranonce++ })
        if (w.seedHeight != keyHeight) {
            synchronized(lock) { current = null; Native.setJob(null, null, 0) }
            if (!checked) {
                // before anything is mined the phone proves that it computes RandomX as the network does
                val pre = Native.prepare(null, false, 1)
                val h = if (pre.startsWith("ok")) Native.selfHash(false) else pre
                if (h != SELF_HASH) throw NodeError(tr("this phone computes RandomX wrongly, mining would be for nothing ($h)", "tento telefon počítá RandomX špatně, těžba by byla k ničemu ($h)"))
                checked = true
            }
            val key = if (w.seedHeight == 0) ByteArray(32) else Hex.reversed(client.blockHash(w.seedHeight))
            MinerState.line = tr("preparing memory for mining", "připravuji paměť pro těžbu")
            MinerState.say(tr("RandomX key from block ${w.seedHeight}; preparing the fast mode (about 2.3 GiB)", "klíč RandomX z bloku ${w.seedHeight}; připravuji rychlý režim (asi 2,3 GiB)"))
            // The fast mode takes 2.3 GiB. A phone with little memory would get it and then be killed by the
            // system, so the fast mode is tried only where the whole memory is about 6 GB or more.
            val mem = android.app.ActivityManager.MemoryInfo()
            (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mem)
            var ans = if (mem.totalMem >= 5_500L * 1024 * 1024) Native.prepare(key, true, Runtime.getRuntime().availableProcessors())
                else "error: " + tr("this phone has too little memory for the fast mode", "tento telefon má na rychlý režim málo paměti")
            if (!ans.startsWith("ok")) {
                MinerState.say(ans)
                MinerState.say(tr("the fast mode failed, trying the light one (many times slower)", "rychlý režim se nepodařil, zkouším lehký (řádově pomalejší)"))
                ans = Native.prepare(key, false, 1)
            }
            MinerState.say(ans)
            if (!ans.startsWith("ok")) throw NodeError(tr("the memory for RandomX could not be prepared", "paměť pro RandomX se nepodařilo připravit"))
            if (ans.startsWith("ok: fast") && Native.selfHash(false) != Native.selfHash(true))
                throw NodeError(tr("the fast and the light mode disagree on this phone", "rychlý a lehký režim se na tomto telefonu neshodují"))
            if (stop) throw IOException("stopped")
            Native.mineStart(cpus)
            keyHeight = w.seedHeight
        }
        synchronized(lock) {
            jobId++
            jobs[jobId] = w
            while (jobs.size > 6) jobs.remove(jobs.keys.first())
            current = w
            currentId = jobId
            if (paused == null) Native.setJob(w.header76, w.target, jobId)
        }
        val now = System.currentTimeMillis()
        if (w.height != MinerState.height && now - lastWorkSaid > 2000) {
            MinerState.say(tr("new work: block ${w.height}, transactions in the template ${w.transactions}", "nová práce: blok ${w.height}, transakcí v šabloně ${w.transactions}"))
            lastWorkSaid = now
        }
        MinerState.height = w.height
        return w
    }

    /** Sends found blocks, watches heat and charging, and keeps the numbers on the screen current. */
    private fun controlLoop(client: NodeClient, limit: Int, onlyCharging: Boolean, threads: Int) {
        var lastHashes = 0L
        var lastTime = System.nanoTime()
        var lastGuard = 0L
        var lastNote = 0L
        var failedSaid = false
        var heat = false
        var heatAt = 0L
        var temp = 0.0
        var thermalNow = 0
        var unpluggedSince = 0L
        var minuteHashes = 0L
        var minuteTime = System.nanoTime()
        var minuteLost = false
        while (!stop) {
            Thread.sleep(250)
            val found = Native.takeFound()
            if (found != null) {
                for (i in found.indices step 2) {
                    val id = found[i]
                    val nonce = found[i + 1]
                    val w = synchronized(lock) {
                        if (id <= submitted) null else {
                            submitted = id
                            if (currentId == id) { current = null; Native.setJob(null, null, 0) }
                            jobs[id]
                        }
                    } ?: continue
                    // Two templates can stand on the same block (the node refreshed the transactions). Once
                    // the node has taken our block on top of it, a second one there would only compete with it.
                    if (w.previous == wonOn) continue
                    submitter.execute { submit(client, w, nonce) }
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastGuard >= 1000) {
                lastGuard = now
                val (t, plugged, thermal) = battery()
                temp = t
                thermalNow = thermal
                // a charger that drops out for a moment (a loose cable, the phone pausing the charge) is not "unplugged"
                if (plugged) unpluggedSince = 0L else if (unpluggedSince == 0L) unpluggedSince = now
                val unplugged = unpluggedSince != 0L && now - unpluggedSince >= 5000
                if (t.isNaN() || thermal >= PowerManager.THERMAL_STATUS_SEVERE || t >= limit) { heat = true; heatAt = now }
                else if (heat && t <= limit - 2 && now - heatAt >= 30_000) heat = false
                val reason = when {
                    onlyCharging && unplugged -> tr("the phone is not charging", "telefon není na nabíječce")
                    t.isNaN() -> tr("the battery temperature cannot be read, so mining waits", "teplotu baterie nelze zjistit, těžba proto čeká")
                    heat -> tr("cooling down: battery %.1f °C, limit %d °C, thermal status %d", "chladnu: baterie %.1f °C, strop %d °C, tepelný stav %d").format(Locale.ROOT, t, limit, thermal)
                    else -> null
                }
                val before = paused
                if ((reason == null) != (before == null)) {
                    synchronized(lock) {
                        paused = reason
                        if (reason != null) Native.setJob(null, null, 0)
                        else current?.let { Native.setJob(it.header76, it.target, currentId) }
                    }
                    MinerState.say(if (reason != null) tr("paused: $reason", "pauza: $reason") else tr("going on (battery %.1f °C)", "pokračuji (baterie %.1f °C)").format(Locale.ROOT, t))
                } else if (reason != null) paused = reason
            }
            val nanos = System.nanoTime()
            if (nanos - lastTime >= 5_000_000_000L) {
                val h = Native.hashes()
                val failed = Native.failed()
                if (failed > 0 && !failedSaid) {
                    failedSaid = true
                    MinerState.say(tr("threads that could not start: $failed", "vlákna, která se nepodařilo spustit: $failed"))
                }
                MinerState.rate = if (h >= lastHashes) (h - lastHashes) / ((nanos - lastTime) / 1e9) else 0.0
                // once a minute a line for the log; a minute in which the counter started again (new key) is skipped
                if (h < minuteHashes) { minuteHashes = h; minuteTime = nanos; minuteLost = true }
                if (nanos - minuteTime >= 60_000_000_000L) {
                    if (!minuteLost) MinerState.say(tr("minute: %.0f H/s, battery %.1f °C, thermal status %d%s", "minuta: %.0f H/s, baterie %.1f °C, tepelný stav %d%s").format(Locale.ROOT,
                        (h - minuteHashes) / ((nanos - minuteTime) / 1e9), temp, thermalNow, if (paused != null) tr(", paused", ", pauza") else ""))
                    minuteHashes = h; minuteTime = nanos; minuteLost = false
                }
                lastHashes = h
                lastTime = nanos
                val pause = paused
                val line = when {
                    pause != null -> tr("paused: $pause", "pauza: $pause")
                    synchronized(lock) { current } == null -> if (keyHeight < 0) tr("preparing", "připravuji") else tr("waiting for work from the node", "čekám na práci od uzlu")
                    else -> tr("mining block %d: %.0f H/s, threads %d, battery %.1f °C", "těžím blok %d: %.0f H/s, počet vláken %d, baterie %.1f °C").format(Locale.ROOT, MinerState.height, MinerState.rate, threads, temp)
                }
                if (!MinerState.line.startsWith(tr("waiting for the node", "čekám na uzel")) || pause != null || synchronized(lock) { current } != null) MinerState.line = line
                if (now - lastNote >= 10_000) {
                    lastNote = now
                    getSystemService(NotificationManager::class.java).notify(1, notification(line + tr("; blocks accepted: ${MinerState.accepted}", "; přijaté bloky: ${MinerState.accepted}")))
                }
            }
        }
    }

    companion object {
        const val CHANNEL = "mining"
        /** The hash of the fixed test input with the fixed test key, computed on a PC from the same sources. */
        const val SELF_HASH = "d8833785120ddada94daa341facb4d612b6b0e8e5caed4aec44692138ab2c4d0"
        /** The thread that ends the last run. A new run waits for it, so the two never share the memory. */
        @Volatile var cleanup: Thread? = null
    }
}
