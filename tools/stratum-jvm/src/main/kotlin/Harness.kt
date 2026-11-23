import org.json.JSONArray
import org.json.JSONObject
import org.polarka.miner.Blocks
import org.polarka.miner.Hex
import org.polarka.miner.JsonDepth
import org.polarka.miner.PoolKeys
import org.polarka.miner.PoolMisbehaves
import org.polarka.miner.PoolWork
import org.polarka.miner.StratumClient
import org.polarka.miner.StratumError
import org.polarka.miner.StratumEvents
import org.polarka.miner.StratumJob
import org.polarka.miner.StratumMath
import org.polarka.miner.StratumUrl
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread

/*
 * Polarka Miner's stratum client on a computer, for tools/stratum_check.py: it connects to a pool, takes jobs the
 * way the app's service does (PoolWork, StratumMath), "mines" with a made-up proof of work (SHA-256 of the
 * RandomX key and the header, as in the pool's own tests), sends shares and says on standard output, one JSON
 * object a line, everything that happens. It connects again when a connection ends.
 *
 *     stratum-jvm HOST PORT USER       mine
 *     stratum-jvm --targets            the share targets of the difficulties 2^-k, k from -16 to 31
 *     stratum-jvm --unit               the checks that need no pool: what the review of 0.3.0 found, each a case
 *
 * Jobs are taken as MinerService takes them since that review: with the difficulty and the key of their own
 * connection only, and with keys that PoolKeys lets through.
 */

private fun say(o: JSONObject) = synchronized(System.out) { println(o.toString()); System.out.flush() }

private fun ev(name: String) = JSONObject().put("ev", name)

class Harness(private val host: String, private val port: Int, private val user: String) {
    private val lock = Any()
    private val keys = PoolKeys()
    private var extranonce2 = 0L
    @Volatile private var latest: PoolWork? = null
    @Volatile private var client: StratumClient? = null
    @Volatile private var connection = 0L

    private inner class Events(private val conn: Long) : StratumEvents {
        lateinit var c: StratumClient
        private var key: ByteArray? = null
        private var keyHeight = -1
        private var difficulty = 0.0
        override fun difficulty(d: Double) {
            synchronized(lock) { difficulty = d }
            say(ev("difficulty").put("d", d))
        }
        override fun seed(key: ByteArray, height: Int) {
            synchronized(lock) { keys.named(key, height); this.key = key; keyHeight = height }
            say(ev("seed").put("height", height).put("key", Hex.encode(key)))
        }
        override fun job(job: StratumJob) {
            val w = synchronized(lock) {
                val k = key ?: return
                if (difficulty <= 0.0) return
                if (!keys.fits(job.height, keyHeight)) { say(ev("misfit").put("height", job.height).put("key", keyHeight)); return }
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

// -- the checks that need no pool -----------------------------------------------------------------------------

private var failed = 0

private fun check(ok: Boolean, what: String) {
    say(ev(if (ok) "ok" else "FAILED").put("what", what))
    if (!ok) failed++
}

private inline fun refused(what: String, call: () -> Unit) {
    try {
        call()
        check(false, what)
    } catch (e: PoolMisbehaves) {
        check(true, what + ": " + e.message)
    }
}

/** A pool of one connection on 127.0.0.1 that plays [script]; the port. */
private fun fakePool(script: (BufferedReader, PrintWriter) -> Unit): Int {
    val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    thread(isDaemon = true) {
        server.use { srv ->
            srv.accept().use { s ->
                runCatching { script(BufferedReader(InputStreamReader(s.getInputStream())), PrintWriter(s.getOutputStream(), true)) }
            }
        }
    }
    return server.localPort
}

private class Seen : StratumEvents {
    val events = java.util.Collections.synchronizedList(ArrayList<String>())
    override fun difficulty(d: Double) { events.add("difficulty") }
    override fun seed(key: ByteArray, height: Int) { events.add("seed $height") }
    override fun job(job: StratumJob) { events.add("job ${job.id}") }
    override fun message(text: String) { events.add("message $text") }
    override fun share(accepted: Boolean, why: String?) { events.add("share $accepted") }
}

/** What the client says when the connection to a pool that plays [script] ends. */
private fun runAgainst(events: Seen, script: (BufferedReader, PrintWriter) -> Unit): String {
    val port = fakePool(script)
    val c = StratumClient(StratumUrl("127.0.0.1", port), "rnorth1x", "x", "PolarkaMiner/unit", events)
    return try { c.run(); "?" } catch (e: StratumError) { "StratumError: " + e.message } catch (e: Exception) { e.javaClass.simpleName + ": " + e.message } catch (e: Throwable) { "THROWABLE " + e.javaClass.simpleName }
}

private const val SUBSCRIBED = """{"id":1,"result":[[["mining.notify","1"]],"0a0b0c0d",4],"error":null}"""
private val JOB = """{"id":null,"method":"mining.notify","params":["j1","${"00".repeat(32)}","${"01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff"}03${"010203"}","ffffffff0100000000000000000000000000",[],"20000000","1d00ffff","6553f100",true]}"""

private fun unit() {
    // H1: a line nested deeper than anything a pool sends is refused before it is read
    check(JsonDepth.of("""{"id":1,"result":[[[]]]}""") == 4, "nesting is counted")
    check(JsonDepth.of("""{"a":"[[[[[[[[[[[[[[[[[[[[[[[["}""") == 1, "brackets inside a string do not count")
    check(JsonDepth.of("""{"a":"\"[[[[[[[[[[[[[[[[[[[[","b":1}""") == 1, "an escaped quote does not end a string")
    check(!JsonDepth.fits("{\"id\":1,\"result\":" + "[".repeat(60_000)), "sixty thousand brackets do not fit")
    val deep = Seen()
    val why = runAgainst(deep) { input, out ->
        input.readLine()
        out.println("{\"id\":1,\"result\":" + "[".repeat(60_000))
        Thread.sleep(500)
    }
    check(why.startsWith("StratumError") && "nested" in why, "a pool that sends a line nested 60,000 deep ends the connection with a StratumError, not the app ($why)")

    // L4: what a pool says is shown as printable ASCII only
    check(StratumClient.plain("a\u2028b\u2029c\u202Ed\u200Be\u0007f") == "abcdef", "no line separator, no change of direction, nothing invisible")

    // L2: work that comes before the address is let in is kept, and given once it is
    val early = Seen()
    runAgainst(early) { input, out ->
        input.readLine(); out.println(SUBSCRIBED)
        input.readLine()                                   // the authorization: answered only after a job
        out.println("""{"id":null,"method":"mining.set_difficulty","params":[0.001]}""")
        out.println(JOB)
        Thread.sleep(300)
        out.println("""{"id":2,"result":true,"error":null}""")
        Thread.sleep(300)
    }
    val jobAt = early.events.indexOf("job j1")
    check(jobAt >= 0, "the job that came before the address was let in is given (${early.events})")

    // N2: blank lines do not add up to a line too long
    val blank = Seen()
    val blankWhy = runAgainst(blank) { input, out ->
        input.readLine(); out.println(SUBSCRIBED)
        input.readLine(); out.println("""{"id":2,"result":true,"error":null}""")
        val spaces = StringBuilder(); repeat(70_000) { spaces.append(" \n") }
        out.print(spaces); out.flush()
        out.println("""{"id":null,"method":"client.show_message","params":["still here"]}""")
        Thread.sleep(300)
    }
    check("message still here" in blank.events, "70,000 blank lines and then a message ($blankWhy)")

    // M1: which keys a pool may make the phone build memory for
    var now = 1_000_000L
    val k = PoolKeys { now }
    val zero = ByteArray(32)
    val a = ByteArray(32) { 1 }
    val b = ByteArray(32) { 2 }
    val c = ByteArray(32) { 3 }
    check(k.named(zero, 0), "the first key")
    check(!k.named(zero, 0), "the same key again is no new key")
    refused("a key for block 0 that is not 32 zero bytes") { PoolKeys().named(a, 0) }
    refused("a key for a block that is no multiple of 2048") { k.named(a, 1000) }
    check(k.named(a, 2048), "the first change of the key")
    now += 3600_000L
    refused("a second change within six hours") { k.named(b, 4096) }
    refused("two keys for one block") { k.named(c, 2048) }
    refused("back to an older key, even one named before") { k.named(zero, 0) }
    now += PoolKeys.CHANGE_MS
    check(k.named(b, 4096), "a change six hours after the last one")
    check(k.fits(4160, 4096) && !k.fits(4160, 2048) && k.fits(-1, 2048) && k.fits(100, 0) && !k.fits(2112, 0), "a job's block leads to the key's block")

    say(ev("unit").put("failed", failed))
    if (failed > 0) kotlin.system.exitProcess(1)
}

fun main(args: Array<String>) {
    if (args.size == 1 && args[0] == "--unit") { unit(); return }
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
