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
    /** Blocks the node took (alone), or shares the pool took (in a pool). */
    @Volatile var accepted = 0
    @Volatile var refused = 0
    @Volatile var height = 0
    /** True while the service mines in a pool: the numbers above then count shares. */
    @Volatile var pool = false
    @Volatile var difficulty = 0.0
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

/** A difficulty for the log: 2^-23 as "2^-23", anything else as a short number. */
fun shownDifficulty(d: Double): String {
    if (d <= 0.0 || !d.isFinite()) return "?"
    val k = Math.round(Math.log(d) / Math.log(2.0)).toInt()
    return if (Math.pow(2.0, k.toDouble()) == d) "2^$k" else String.format(Locale.ROOT, "%.3g", d)
}

/**
 * Mines while the app is not on the screen. Android stops an app that leaves the screen unless it runs a
 * service with a permanent notification, so this is that service: the notification says that the phone is
 * mining and how fast. It holds a wake lock, gets work, hands the header to the native workers and sends what
 * they find. It pauses when the battery is too warm, when the system reports severe heat, and (if asked) when
 * the phone is not charging. No keys: it knows one address to be paid to.
 *
 * Alone (a node or a wallet server hands out block templates) it puts each block together itself and sends a
 * block when one is found. In a pool (stratum) the pool hands out jobs, the phone sends shares, and the pool pays
 * for them by its own rules.
 */
class MinerService : Service() {
    private var thread: Thread? = null
    private var templates: Thread? = null
    private var reader: Thread? = null
    @Volatile private var stop = false
    @Volatile private var node: NodeClient? = null
    private var wake: PowerManager.WakeLock? = null

    private val lock = Any()
    private val jobs = LinkedHashMap<Long, Work>()
    private var jobId = 0L
    private var current: Mineable? = null
    private var keyHeight = -1
    private var preparedKey: ByteArray? = null
    private var submitted = 0L
    @Volatile private var paused: String? = null
    private var cpus = IntArray(0)
    private val submitter = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var wonOn = ""
    private var checked = false

    // In a pool. Everything below is guarded by `lock`, unless it is volatile.
    @Volatile private var stratum: StratumClient? = null
    private var connection = 0L                      // the connection the pool's work belongs to
    private var poolKey: ByteArray? = null           // the RandomX key the pool named last
    private var poolKeyHeight = -1
    private var poolDifficulty = 0.0
    private var poolLatest: PoolWork? = null         // the newest work, not yet given to the workers
    private var extranonce2 = 0L
    private val poolJobs = LinkedHashMap<Long, PoolWork>()
    private val keyChanges = ArrayDeque<Long>()
    private val poolWake = java.util.concurrent.ArrayBlockingQueue<Unit>(1)   // one waiting wake-up is enough
    @Volatile private var lastRefusal: String? = null
    @Volatile private var lastRefusalAt = 0L

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
        stratum?.close()
        wakePool()
        MinerState.line = tr("stopping", "zastavuji")
        val control = thread
        val asking = templates
        val reading = reader
        val held = wake
        // Nothing here waits on the main thread. In this order, on a thread of its own: the loops end
        // (a dataset that is being built is finished first), what was found is sent, the workers
        // stop, the memory is given back. A run started right after waits for all of it.
        cleanup = Thread({
            try { control?.join(); asking?.join(); reading?.join() } catch (e: InterruptedException) { }
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
                    refresh = true          // the tip moved: ask for the next block at once
                    client.interrupt()
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

    private fun version(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

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
            val inPool = StratumUrl.isStratum(url)
            require(inPool || url.startsWith("http://") || url.startsWith("https://")) {
                tr("the address of the work must start with stratum+tcp:// (a pool), http:// or https:// (a node or a wallet server)",
                    "adresa práce musí začínat stratum+tcp:// (pool), http:// nebo https:// (uzel nebo server peněženek)")
            }
            val (hrp, pay) = Blocks.addressScript(address)
            cpus = fastestCores().take(threads).map { it.first }.toIntArray()
            val shown = address.take(10) + "…" + address.takeLast(6)
            val care = tr("threads $threads, battery limit $limit °C, ", "počet vláken $threads, strop teploty baterie $limit °C, ") +
                if (onlyCharging) tr("only while charging", "jen při nabíjení") else tr("also when not charging", "i bez nabíječky")
            MinerState.pool = inPool
            MinerState.difficulty = 0.0
            if (inPool) {
                val where = StratumUrl.parse(url)
                val worker = (p.getString("user", "") ?: "").trim()
                require(worker.length <= 32 && worker.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }) {
                    tr("a worker name has at most 32 letters, digits, - and _", "jméno pracovníka má nejvýš 32 písmen bez diakritiky, číslic, - a _")
                }
                val lower = address.lowercase(Locale.ROOT)
                val user = if (worker.isEmpty()) lower else "$lower.$worker"
                val password = (p.getString("password", "") ?: "").ifEmpty { "x" }
                MinerState.say(tr("starting in the pool $where: address $shown (${Blocks.PREFIXES[hrp]}), ", "začínám v poolu $where: adresa $shown (${Blocks.PREFIXES[hrp]}), ") +
                    (if (worker.isEmpty()) "" else tr("worker $worker, ", "pracovník $worker, ")) + care)
                templates = Thread({ poolWorkLoop() }, "miner-pool-work").apply { start() }
                reader = Thread({ stratumLoop(where, user, password) }, "miner-stratum").apply { start() }
                controlLoop(null, limit, onlyCharging, threads)
                reader?.join(3000)
                templates?.join(3000)
            } else {
                val client = NodeClient(url, p.getString("user", "") ?: "", p.getString("password", "") ?: "")
                node = client
                extranonce = SecureRandom().nextLong()
                MinerState.say(tr("starting: address $shown (${Blocks.PREFIXES[hrp]}), ", "začínám: adresa $shown (${Blocks.PREFIXES[hrp]}), ") + care)
                templates = Thread({ templateLoop(client, pay) }, "miner-templates").apply { start() }
                controlLoop(client, limit, onlyCharging, threads)
                templates?.join(3000)
            }
        } catch (e: IllegalArgumentException) {
            MinerState.say(tr("cannot start: ${e.message}", "nelze začít: ${e.message}"))
            MinerState.line = tr("cannot start: ${e.message}", "nelze začít: ${e.message}")
            MinerState.running = false
            stopSelf()
        }
    }

    // -- alone: a node or a wallet server hands out block templates ----------------------------------------------

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
        var polledPrevious = ""
        var polledAt = 0L
        while (!stop) {
            if (refresh) { refresh = false; lp = null; polledPrevious = "" }
            try {
                val asked = System.currentTimeMillis()
                val t = client.template(lp)
                val previous = t.optString("previousblockhash")
                val now = System.currentTimeMillis()
                // Without a long poll the same template comes back every few seconds. The work in hand is
                // kept while the tip is the same, and renewed twice a minute for the new transactions and time.
                val same = previous == polledPrevious && now - polledAt < 30_000 && synchronized(lock) { current != null }
                if (same && (lp == null || client.rest)) {
                    failingSince = 0L
                    lastMessage = ""
                    // A wallet server ends a long poll after 25 seconds also when nothing changed. The wait
                    // goes on with the identifier it gave, and an answer that came at once is not asked for
                    // again at once.
                    if (lp != null) {
                        lp = t.optString("longpollid").ifEmpty { null }
                        if (now - asked < 2000) nap(2000)
                    }
                } else {
                    val w = newWork(client, t, pay)
                    lp = w.longPollId
                    polledPrevious = previous
                    polledAt = now
                    failingSince = 0L
                    lastMessage = ""
                }
                if (lp == null) {
                    val end = System.currentTimeMillis() + 5000
                    while (!stop && !refresh && System.currentTimeMillis() < end) Thread.sleep(100)
                }
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

    /**
     * Builds the cache and the dataset for a RandomX key and starts the workers on it. Before the first key the
     * phone proves that it computes RandomX as the network does. Takes minutes; nothing is hashed meanwhile.
     */
    private fun prepareKey(key: ByteArray, height: Int) {
        synchronized(lock) { current = null; Native.setJob(null, null, 0) }
        if (!checked) {
            // before anything is mined the phone proves that it computes RandomX as the network does
            val pre = Native.prepare(null, false, 1)
            val h = if (pre.startsWith("ok")) Native.selfHash(false) else pre
            if (h != SELF_HASH) throw NodeError(tr("this phone computes RandomX wrongly, mining would be for nothing ($h)", "tento telefon počítá RandomX špatně, těžba by byla k ničemu ($h)"))
            checked = true
        }
        MinerState.line = tr("preparing memory for mining", "připravuji paměť pro těžbu")
        MinerState.say(tr("RandomX key from block $height; preparing the fast mode (about 2.3 GiB)", "klíč RandomX z bloku $height; připravuji rychlý režim (asi 2,3 GiB)"))
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
        preparedKey = null
        if (!ans.startsWith("ok")) throw NodeError(tr("the memory for RandomX could not be prepared", "paměť pro RandomX se nepodařilo připravit"))
        if (ans.startsWith("ok: fast") && Native.selfHash(false) != Native.selfHash(true))
            throw NodeError(tr("the fast and the light mode disagree on this phone", "rychlý a lehký režim se na tomto telefonu neshodují"))
        if (stop) throw IOException("stopped")
        Native.mineStart(cpus)
        preparedKey = key.copyOf()
    }

    private fun newWork(client: NodeClient, t: org.json.JSONObject, pay: ByteArray): Work {
        val w = Blocks.assemble(t, pay, synchronized(lock) { extranonce++ })
        if (w.seedHeight != keyHeight) {
            val key = if (w.seedHeight == 0) ByteArray(32) else Hex.reversed(client.blockHash(w.seedHeight))
            prepareKey(key, w.seedHeight)
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

    // -- in a pool: stratum ----------------------------------------------------------------------------------------

    private fun wakePool() {
        poolWake.offer(Unit)
    }

    /**
     * What one connection says. The client calls these on its reading thread; they only take note and wake the
     * thread that gives work to the workers, so that the connection is read also while memory is being built.
     */
    private inner class PoolEvents(private val conn: Long) : StratumEvents {
        lateinit var client: StratumClient

        override fun difficulty(d: Double) {
            synchronized(lock) { if (conn == connection) poolDifficulty = d }
        }

        override fun seed(key: ByteArray, height: Int) {
            synchronized(lock) {
                if (conn != connection || poolKey?.contentEquals(key) == true) return
                val now = System.currentTimeMillis()
                // the key of the network changes every 2048 blocks, about every three days
                if (poolKey != null) keyChanges.addLast(now)
                while (keyChanges.isNotEmpty() && now - keyChanges.first() > 30 * 60_000L) keyChanges.removeFirst()
                if (keyChanges.size > 3) throw PoolMisbehaves(tr("the pool changes the RandomX key too often; it costs the phone minutes each time", "pool příliš často mění klíč RandomX; telefon to pokaždé stojí minuty"))
                poolKey = key.copyOf()
                poolKeyHeight = height
            }
        }

        override fun job(job: StratumJob) {
            synchronized(lock) {
                if (conn != connection) return
                val key = poolKey ?: return              // no key yet: the work could not be hashed
                if (poolDifficulty <= 0.0) return        // no difficulty yet: no share target
                extranonce2++
                val e2 = Blocks.le(extranonce2, client.extranonce2Size).reversedArray()
                poolLatest = PoolWork(job, e2, StratumMath.header76(job, client.extranonce1, e2), StratumMath.target(poolDifficulty),
                    key, poolKeyHeight, poolDifficulty, conn)
            }
            wakePool()
        }

        override fun message(text: String) {
            if (text.isNotEmpty()) MinerState.say(tr("the pool says: ", "pool říká: ") + text)
        }

        override fun share(accepted: Boolean, why: String?) {
            if (accepted) {
                MinerState.accepted++
                if (MinerState.accepted == 1) MinerState.say(tr("the pool accepted the first share", "pool přijal první podíl"))
                return
            }
            MinerState.refused++
            val now = System.currentTimeMillis()
            if (why != lastRefusal || now - lastRefusalAt > 60_000) {
                MinerState.say(tr("the pool refused a share: $why", "pool podíl nepřijal: $why"))
                lastRefusal = why
                lastRefusalAt = now
            }
        }
    }

    /** Keeps one connection to the pool; when it ends, a new one after a wait that grows while they keep failing. */
    private fun stratumLoop(where: StratumUrl, user: String, password: String) {
        var wait = 5_000L
        var lastSaid = ""
        val agent = "PolarkaMiner/" + version()
        while (!stop) {
            val conn = synchronized(lock) { connection }
            val events = PoolEvents(conn)
            val c = StratumClient(where, user, password, agent, events)
            events.client = c
            stratum = c
            if (stop) { c.close(); break }              // stopped between the check above and now
            val began = System.currentTimeMillis()
            var why: String
            var after = wait
            try {
                c.run()                                  // returns only by an exception
                why = "?"
            } catch (e: PoolMisbehaves) {
                why = e.message ?: "?"
                after = 10 * 60_000L
            } catch (e: StratumError) {
                why = e.message ?: "?"
                if (!c.authorized) after = maxOf(wait, 60_000L)   // refused before mining: no hurry to ask again
            } catch (e: IOException) {
                why = e.message ?: e.javaClass.simpleName
            } catch (e: Exception) {
                why = e.message ?: e.javaClass.simpleName
            }
            stratum = null
            synchronized(lock) {
                connection++
                poolLatest = null
                poolJobs.clear()
                if (current is PoolWork) { current = null; Native.setJob(null, null, 0) }
            }
            if (stop) break
            if (System.currentTimeMillis() - began > 120_000) { wait = 5_000L; if (after < 60_000L) after = wait }
            val text = tr("the pool connection ended (%s); trying again in %d s", "spojení s poolem skončilo (%s); znovu za %d s").format(Locale.ROOT, why, after / 1000)
            if (text != lastSaid) { MinerState.say(text); lastSaid = text }
            MinerState.line = tr("waiting for the pool", "čekám na pool") + ": " + why
            nap(after)
            wait = minOf(wait * 2, 120_000L)
        }
    }

    /** Gives the newest work of the pool to the workers, building the memory for a new key first. */
    private fun poolWorkLoop() {
        while (!stop) {
            poolWake.poll(1, java.util.concurrent.TimeUnit.SECONDS)
            if (stop) break
            val w = synchronized(lock) { poolLatest } ?: continue
            if (!w.key.contentEquals(preparedKey)) {
                try {
                    prepareKey(w.key, w.keyHeight)      // minutes; the connection is read meanwhile on its own thread
                } catch (e: Exception) {
                    if (stop) break
                    MinerState.say(e.message ?: e.javaClass.simpleName)
                    MinerState.line = tr("cannot mine: ", "nelze těžit: ") + (e.message ?: "?")
                    stratum?.close()
                    nap(30_000)
                }
                wakePool()                              // newer work may have come meanwhile
                continue
            }
            var given = false
            synchronized(lock) {
                if (w === poolLatest && w.connection == connection) {
                    poolLatest = null
                    jobId++
                    if (w.job.clean) poolJobs.clear()
                    poolJobs[jobId] = w
                    while (poolJobs.size > 8) poolJobs.remove(poolJobs.keys.first())
                    current = w
                    currentId = jobId
                    if (paused == null) Native.setJob(w.header76, w.target, jobId)
                    given = true
                }
            }
            if (!given) continue
            val now = System.currentTimeMillis()
            val height = w.job.height
            if ((height != MinerState.height || w.difficulty != MinerState.difficulty) && now - lastWorkSaid > 2000) {
                MinerState.say(tr("new work from the pool: ", "nová práce od poolu: ") + (if (height >= 0) tr("block $height, ", "blok $height, ") else "") +
                    tr("difficulty ", "obtížnost ") + shownDifficulty(w.difficulty))
                lastWorkSaid = now
            }
            if (height >= 0) MinerState.height = height
            MinerState.difficulty = w.difficulty
        }
    }

    /** Shares the workers found: each one to the pool, on the connection whose job it is. */
    private fun shares(found: LongArray) {
        val c = stratum
        for (i in found.indices step 2) {
            val id = found[i]
            val nonce = found[i + 1]
            val w = synchronized(lock) { poolJobs[id]?.takeIf { it.connection == connection } } ?: continue
            if (c == null) continue
            submitter.execute { c.submit(w.job, w.extranonce2, nonce) }
        }
    }

    // -- both ------------------------------------------------------------------------------------------------------

    /** Blocks the workers found, alone: each one to the node, once for each block it stands on. */
    private fun blocks(client: NodeClient, found: LongArray) {
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

    /** Sends what was found, watches heat and charging, and keeps the numbers on the screen current. */
    private fun controlLoop(client: NodeClient?, limit: Int, onlyCharging: Boolean, threads: Int) {
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
        var minuteAccepted = 0
        var minuteRefused = 0
        val inPool = client == null
        while (!stop) {
            Thread.sleep(250)
            val found = Native.takeFound()
            if (found != null) {
                if (client == null) shares(found) else blocks(client, found)
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
                    val shares = if (!inPool) "" else tr(", shares +%d accepted, +%d refused", ", podíly +%d přijaté, +%d nepřijaté").format(Locale.ROOT,
                        MinerState.accepted - minuteAccepted, MinerState.refused - minuteRefused)
                    if (!minuteLost) MinerState.say(tr("minute: %.0f H/s, battery %.1f °C, thermal status %d%s", "minuta: %.0f H/s, baterie %.1f °C, tepelný stav %d%s").format(Locale.ROOT,
                        (h - minuteHashes) / ((nanos - minuteTime) / 1e9), temp, thermalNow, if (paused != null) tr(", paused", ", pauza") else "") + shares)
                    minuteHashes = h; minuteTime = nanos; minuteLost = false
                    minuteAccepted = MinerState.accepted; minuteRefused = MinerState.refused
                }
                lastHashes = h
                lastTime = nanos
                val pause = paused
                val idle = synchronized(lock) { current } == null
                val line = when {
                    pause != null -> tr("paused: $pause", "pauza: $pause")
                    inPool && idle -> if (stratum == null) tr("waiting for the pool", "čekám na pool") else if (preparedKey == null) tr("preparing", "připravuji") else tr("waiting for work from the pool", "čekám na práci od poolu")
                    idle -> if (keyHeight < 0) tr("preparing", "připravuji") else tr("waiting for work from the node", "čekám na práci od uzlu")
                    inPool -> tr("mining in the pool: %.0f H/s, difficulty %s, threads %d, battery %.1f °C", "těžím v poolu: %.0f H/s, obtížnost %s, počet vláken %d, baterie %.1f °C").format(Locale.ROOT,
                        MinerState.rate, shownDifficulty(MinerState.difficulty), threads, temp)
                    else -> tr("mining block %d: %.0f H/s, threads %d, battery %.1f °C", "těžím blok %d: %.0f H/s, počet vláken %d, baterie %.1f °C").format(Locale.ROOT, MinerState.height, MinerState.rate, threads, temp)
                }
                val waiting = MinerState.line.startsWith(tr("waiting for the node", "čekám na uzel")) || MinerState.line.startsWith(tr("waiting for the pool", "čekám na pool")) ||
                    MinerState.line.startsWith(tr("cannot mine", "nelze těžit"))
                if (!waiting || pause != null || !idle) MinerState.line = line
                if (now - lastNote >= 10_000) {
                    lastNote = now
                    val counted = if (inPool) tr("; shares accepted: ${MinerState.accepted}", "; přijaté podíly: ${MinerState.accepted}")
                        else tr("; blocks accepted: ${MinerState.accepted}", "; přijaté bloky: ${MinerState.accepted}")
                    getSystemService(NotificationManager::class.java).notify(1, notification(line + counted))
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
