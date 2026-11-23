import org.json.JSONArray
import org.json.JSONObject
import org.polarka.miner.Blocks
import org.polarka.miner.Hex
import org.polarka.miner.PoolWork
import org.polarka.miner.StratumClient
import org.polarka.miner.StratumEvents
import org.polarka.miner.StratumJob
import org.polarka.miner.StratumMath
import org.polarka.miner.StratumUrl
import java.security.MessageDigest

/*
 * Polarka Miner's stratum client on a computer, for tools/stratum_check.py: it connects to a pool, takes jobs the
 * way the app's service does (PoolWork, StratumMath), "mines" with a made-up proof of work (SHA-256 of the
 * RandomX key and the header, as in the pool's own tests), sends shares and says on standard output, one JSON
 * object a line, everything that happens. It connects again when a connection ends.
 *
 *     stratum-jvm HOST PORT USER       mine
 *     stratum-jvm --targets            the share targets of the difficulties 2^-k, k from -16 to 31
 */

private fun say(o: JSONObject) = synchronized(System.out) { println(o.toString()); System.out.flush() }

private fun ev(name: String) = JSONObject().put("ev", name)

class Harness(private val host: String, private val port: Int, private val user: String) {
    private val lock = Any()
    private var key: ByteArray? = null
    private var keyHeight = -1
    private var difficulty = 0.0
    private var extranonce2 = 0L
    @Volatile private var latest: PoolWork? = null
    @Volatile private var client: StratumClient? = null
    @Volatile private var connection = 0L

    private inner class Events(private val conn: Long) : StratumEvents {
        lateinit var c: StratumClient
        override fun difficulty(d: Double) {
            synchronized(lock) { difficulty = d }
            say(ev("difficulty").put("d", d))
        }
        override fun seed(key: ByteArray, height: Int) {
            synchronized(lock) { this@Harness.key = key; keyHeight = height }
            say(ev("seed").put("height", height).put("key", Hex.encode(key)))
        }
        override fun job(job: StratumJob) {
            val w = synchronized(lock) {
                val k = key ?: return
                if (difficulty <= 0.0) return
                extranonce2++
                val e2 = Blocks.le(extranonce2, c.extranonce2Size).reversedArray()
                PoolWork(job, e2, StratumMath.header76(job, c.extranonce1, e2), StratumMath.target(difficulty), k, keyHeight, difficulty, conn)
            }
            latest = w
            say(ev("job").put("id", job.id).put("clean", job.clean).put("height", job.height).put("d", w.difficulty))
        }
        override fun message(text: String) = say(ev("message").put("text", text))
        override fun share(accepted: Boolean, why: String?) = say(ev("share").put("accepted", accepted).put("why", why ?: JSONObject.NULL))
    }

    fun connect() {
        while (true) {
            val conn = ++connection
            val events = Events(conn)
            val c = StratumClient(StratumUrl(host, port), user, "x", "PolarkaMiner/harness", events)
            events.c = c
            client = c
            say(ev("connecting").put("conn", conn))
            try {
                c.run()
            } catch (e: Exception) {
                say(ev("closed").put("why", e.message ?: e.javaClass.simpleName))
            }
            client = null
            latest = null
            Thread.sleep(1000)
        }
    }

    private fun meets(hash: ByteArray, target: ByteArray): Boolean {
        for (i in 31 downTo 0) {
            val a = hash[i].toInt() and 0xff
            val b = target[i].toInt() and 0xff
            if (a < b) return true
            if (a > b) return false
        }
        return true
    }

    fun mine() {
        val sha = MessageDigest.getInstance("SHA-256")
        var w: PoolWork? = null
        var nonce = 0L
        while (true) {
            val now = latest
            if (now == null) { Thread.sleep(20); continue }
            if (now !== w) { w = now; nonce = 0 }
            sha.reset()
            sha.update(w.key)
            val hash = sha.digest(w.header76 + Blocks.le(nonce, 4))
            if (meets(hash, w.target)) {
                val c = client
                if (c != null && w.connection == connection && c.submit(w.job, w.extranonce2, nonce)) {
                    say(ev("submitted").put("job", w.job.id).put("nonce", nonce))
                    Thread.sleep(300)           // well within the pool's limit of shares a second
                }
            }
            nonce++
            if (nonce > 0xffffffffL) latest = null
        }
    }
}

fun main(args: Array<String>) {
    if (args.size == 1 && args[0] == "--targets") {
        val all = JSONArray()
        for (k in -16..31) all.put(JSONArray().put(k).put(Hex.encode(StratumMath.target(Math.pow(2.0, -k.toDouble())).reversedArray())))
        say(ev("targets").put("targets", all))
        return
    }
    require(args.size == 3) { "stratum-jvm HOST PORT USER, or stratum-jvm --targets" }
    val h = Harness(args[0], args[1].toInt(), args[2])
    Thread({ h.mine() }, "mine").apply { isDaemon = true; start() }
    h.connect()
}
