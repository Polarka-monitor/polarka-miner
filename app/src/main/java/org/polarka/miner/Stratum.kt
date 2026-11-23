package org.polarka.miner

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mining in a pool over stratum, the way TrueNorth's pools and their miner IronMiner speak it
 * (truenorth-ironminer, a cpuminer-opt for rx/north; the pool side is polarka_agent/stratum.py):
 *
 *     -> mining.subscribe [agent]           <- [[...], extranonce1, size of extranonce2]
 *     -> mining.authorize [user, password]  <- true
 *     <- mining.set_difficulty [d]
 *     <- mining.set_randomx_seed [key, h]   the RandomX key as getblockhash shows a hash, and its height
 *     <- mining.notify [job, previous, coinbase1, coinbase2, branch, version, bits, time, clean]
 *     -> mining.submit [user, job, extranonce2, time, nonce]    <- true, or an error
 *
 * The phone hashes the plain 80-byte header and sends every nonce whose hash is at or below the share target.
 * The pool puts the block together itself, so the phone sends shares and never a block. No keys anywhere:
 * the user name is the address the pool pays to.
 *
 * This file has no Android in it, so that it can be tested on a computer against the pool's own code.
 */

/** The pool said no, or said something that is not stratum. The message is fit for the screen. */
open class StratumError(message: String) : Exception(message)

/**
 * How deeply a JSON text nests its brackets and braces, outside its strings. Android's JSON reader calls itself
 * once for every level and knows no limit, so a line of tens of thousands of brackets would overflow the stack of
 * the thread that reads it, and Android ends the app for that (review of 0.3.0, H1). A line is therefore measured
 * before it is read: nothing a pool or a node sends nests deeper than a few levels.
 */
object JsonDepth {
    const val MOST = 16

    /** The deepest level of [text], counting stops once it is past [most]. */
    fun of(text: String, most: Int = MOST): Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[', '{' -> { depth++; if (depth > deepest) { deepest = depth; if (deepest > most) return deepest } }
                ']', '}' -> depth--
            }
        }
        return deepest
    }

    fun fits(text: String, most: Int = MOST): Boolean = of(text, most) <= most
}

/** Where a pool listens: stratum+tcp://host:port. */
class StratumUrl(val host: String, val port: Int) {
    override fun toString(): String = SCHEME + (if (host.contains(':')) "[$host]" else host) + ":" + port

    companion object {
        const val SCHEME = "stratum+tcp://"

        fun isStratum(url: String): Boolean = url.trim().lowercase(Locale.ROOT).startsWith(SCHEME)

        private fun hostChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '_' || c == ':'

        fun parse(input: String): StratumUrl {
            val s = input.trim()
            val bad = tr("a pool's address has the form stratum+tcp://host:port, for example stratum+tcp://pool.polarka.org:3333",
                "adresa poolu má tvar stratum+tcp://host:port, například stratum+tcp://pool.polarka.org:3333")
            require(s.lowercase(Locale.ROOT).startsWith(SCHEME)) { bad }
            val rest = s.substring(SCHEME.length).trimEnd('/')
            val host: String
            val port: String
            if (rest.startsWith("[")) {
                val end = rest.indexOf(']')
                require(end > 1 && end + 2 < rest.length && rest[end + 1] == ':') { bad }
                host = rest.substring(1, end)
                port = rest.substring(end + 2)
            } else {
                val colon = rest.lastIndexOf(':')
                require(colon > 0 && rest.indexOf(':') == colon) { bad }
                host = rest.substring(0, colon)
                port = rest.substring(colon + 1)
            }
            val number = port.toIntOrNull()
            require(number != null && number in 1..65535 && port.all { it in '0'..'9' }) { bad }
            require(host.isNotEmpty() && host.length <= 253 && host.all { hostChar(it) }) { bad }
            return StratumUrl(host, number)
        }
    }
}

/** One job from mining.notify, checked. [previous] is in the order the header holds it. */
class StratumJob(
    val id: String,
    val previous: ByteArray,
    val coinbase1: ByteArray,
    val coinbase2: ByteArray,
    val branch: List<ByteArray>,
    val version: Long,
    /** Eight hexadecimal digits, in lower case. */
    val bits: String,
    /** Eight hexadecimal digits exactly as the pool sent them; a share carries them back unchanged. */
    val time: String,
    val clean: Boolean,
) {
    /** The height the coinbase names (BIP34), or -1 when it cannot be read; only for the log. */
    val height: Int = StratumMath.heightOf(coinbase1)

    companion object {
        const val MOST_COINBASE = 4096          // bytes of each half of the coinbase
        const val MOST_BRANCH = 32              // a branch of 32 hashes is a block of four billion transactions

        /** A part of a job, named in both languages for what the screen says about it. */
        private class Part(val en: String, val cs: String)

        private val PREVIOUS = Part("previous block", "předchozí blok")
        private val COINBASE1 = Part("first part of the coinbase", "první část odměnové transakce")
        private val COINBASE2 = Part("second part of the coinbase", "druhá část odměnové transakce")
        private val BRANCH = Part("branch", "větev")
        private val VERSION = Part("version", "verze")
        private val BITS = Part("bits", "obtížnost bloku")
        private val TIME = Part("time", "čas")

        private fun without(what: Part) = StratumError(tr("the pool sent a job without its ${what.en}", "pool poslal práci bez položky: ${what.cs}"))
        private fun wrong(what: Part) = StratumError(tr("the pool sent a job with a wrong ${what.en}", "pool poslal práci se špatnou položkou: ${what.cs}"))

        private fun hex(s: Any?, bytes: Int, what: Part): ByteArray {
            val t = s as? String ?: throw without(what)
            if (t.length != 2 * bytes) throw wrong(what)
            return try { Hex.decode(t) } catch (e: IllegalArgumentException) { throw wrong(what) }
        }

        private fun hexAny(s: Any?, most: Int, what: Part): ByteArray {
            val t = s as? String ?: throw without(what)
            if (t.length > 2 * most) throw StratumError(tr("the pool sent too long a ${what.en}", "pool poslal příliš dlouhou položku: ${what.cs}"))
            return try { Hex.decode(t) } catch (e: IllegalArgumentException) { throw wrong(what) }
        }

        /** Eight hexadecimal digits, as they came. */
        private fun hex8(s: Any?, what: Part): String {
            val t = s as? String
            if (t == null || t.length != 8 || !t.all { Character.digit(it, 16) >= 0 }) throw wrong(what)
            return t
        }

        /** [job, previous, coinbase1, coinbase2, branch, version, bits, time, clean] */
        fun parse(params: JSONArray): StratumJob {
            if (params.length() < 9) throw StratumError(tr("the pool sent a job that is too short", "pool poslal neúplnou práci"))
            val id = params.opt(0) as? String
            if (id == null || id.isEmpty() || id.length > 64 || !id.all { it in ' '..'~' }) {
                throw StratumError(tr("the pool sent a job with a wrong name", "pool poslal práci se špatným označením"))
            }
            val previous = StratumMath.previous(hex(params.opt(1), 32, PREVIOUS))
            val coinbase1 = hexAny(params.opt(2), MOST_COINBASE, COINBASE1)
            val coinbase2 = hexAny(params.opt(3), MOST_COINBASE, COINBASE2)
            val list = params.opt(4) as? JSONArray ?: throw without(BRANCH)
            if (list.length() > MOST_BRANCH) throw StratumError(tr("the pool sent too long a branch", "pool poslal příliš dlouhou větev"))
            val branch = (0 until list.length()).map { hex(list.opt(it), 32, BRANCH) }
            val version = hex8(params.opt(5), VERSION).toLong(16)
            val bits = hex8(params.opt(6), BITS).lowercase(Locale.ROOT)
            val time = hex8(params.opt(7), TIME)
            val clean = params.opt(8) == true
            return StratumJob(id, previous, coinbase1, coinbase2, branch, version, bits, time, clean)
        }
    }
}

object StratumMath {
    private val TWO96 = BigDecimal(BigInteger.ONE.shiftLeft(96))
    private val LOWER = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
    private val HIGHEST = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)

    /**
     * The share target for a difficulty, as IronMiner's diff_to_hash() makes it: the upper 128 bits are
     * 2^96 / difficulty (rounded down), the lower 128 bits are all ones. For a difficulty 2^-k that is exactly
     * 2^(224 + k) + 2^128 - 1, which is what the pool checks a share against. 32 bytes, least significant first.
     */
    fun target(difficulty: Double): ByteArray {
        if (!difficulty.isFinite() || difficulty <= 0.0) throw StratumError(tr("the pool sent a difficulty that cannot be used", "pool poslal nepoužitelnou obtížnost"))
        val upper = TWO96.divide(BigDecimal(difficulty), 0, RoundingMode.FLOOR).toBigIntegerExact()
        val t = if (upper.bitLength() > 128) HIGHEST else upper.shiftLeft(128).or(LOWER)
        return le32(t)
    }

    /** A number of at most 256 bits to 32 bytes, least significant first. */
    fun le32(n: BigInteger): ByteArray {
        require(n.signum() >= 0 && n.bitLength() <= 256) { "le32: out of range" }
        val be = n.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until minOf(32, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    /** The previous block's hash as stratum sends it (the bytes of every four turned around) to the header's order. */
    fun previous(stratum: ByteArray): ByteArray {
        require(stratum.size == 32) { "previous: 32 bytes" }
        return ByteArray(32) { i -> stratum[(i / 4) * 4 + 3 - i % 4] }
    }

    /** The 76 bytes of the header before the nonce, for one job and one extranonce2. */
    fun header76(job: StratumJob, extranonce1: ByteArray, extranonce2: ByteArray): ByteArray {
        var root = Blocks.dsha(job.coinbase1 + extranonce1 + extranonce2 + job.coinbase2)
        for (step in job.branch) root = Blocks.dsha(root + step)
        return Blocks.le(job.version, 4) + job.previous + root + Blocks.le(job.time.toLong(16), 4) + Hex.reversed(job.bits)
    }

    /** The nonce as a share carries it: the number in the last four bytes of the header, little-endian, in hexadecimal. */
    fun nonceHex(nonce: Long): String = String.format(Locale.ROOT, "%08x", nonce and 0xffffffffL)

    /**
     * The height in a coinbase that starts the usual way (version, one input, the null previous output, the input
     * script beginning with the height, BIP34); -1 otherwise.
     */
    fun heightOf(coinbase1: ByteArray): Int {
        val at = 4 + 1 + 32 + 4                 // version, input count, previous output
        if (coinbase1.size < at + 2 || coinbase1[4].toInt() != 1) return -1
        for (i in 5 until 37) if (coinbase1[i].toInt() != 0) return -1
        for (i in 37 until 41) if (coinbase1[i].toInt() != -1) return -1
        val scriptLength = coinbase1[at].toInt() and 0xff
        if (scriptLength == 0 || scriptLength >= 0xfd) return -1
        val op = coinbase1[at + 1].toInt() and 0xff
        if (op == 0) return 0
        if (op in 0x51..0x60) return op - 0x50
        if (op !in 1..4 || coinbase1.size < at + 2 + op) return -1
        var h = 0L
        for (i in 0 until op) h = h or ((coinbase1[at + 2 + i].toLong() and 0xff) shl (8 * i))
        return if (h in 0..Int.MAX_VALUE.toLong()) h.toInt() else -1
    }
}

/**
 * One job of one connection, made ready for the workers: the header for one extranonce2, the share target of the
 * difficulty that held when the job came, and the RandomX key the pool had named by then.
 */
class PoolWork(
    val job: StratumJob,
    val extranonce2: ByteArray,
    override val header76: ByteArray,
    override val target: ByteArray,
    val key: ByteArray,
    val keyHeight: Int,
    val difficulty: Double,
    val connection: Long,
) : Mineable

/** A pool that keeps changing the RandomX key, or names one that cannot be: every change costs the phone minutes of building memory. */
class PoolMisbehaves(message: String) : StratumError(message)

/**
 * Which RandomX keys a pool may make the phone build its memory for (review of 0.3.0, M1). TrueNorth takes the key
 * from the block at a multiple of 2048 and changes it every 2048 blocks, about every three days, so:
 *
 * - a key's height is a multiple of 2048, and a job's height ([Blocks.seedHeight]) must lead to it;
 * - one height has one key, and the height never goes back;
 * - after the first key, the key changes at most once in [CHANGE_MS]; an honest pool needs once in three days.
 *
 * What it has accepted it keeps for the whole run, across connections, so that a pool cannot start afresh by
 * ending the connection. Not thread-safe: the caller holds its lock.
 */
class PoolKeys(private val clock: () -> Long = System::currentTimeMillis) {
    private val byHeight = HashMap<Int, ByteArray>()
    private var highest = -1
    private var changedAt = 0L

    /**
     * Checks a key that the pool named for [height]; throws [PoolMisbehaves] when it cannot be the network's. True
     * when it is a key not seen before, which the phone will build memory for.
     */
    fun named(key: ByteArray, height: Int): Boolean {
        if (height < 0 || height % Blocks.EPOCH != 0) {
            throw PoolMisbehaves(tr("the pool named a RandomX key for block $height, and the network takes its keys only from every 2048th block",
                "pool uvedl klíč RandomX z bloku $height, ale síť bere klíče jen z každého 2048. bloku"))
        }
        if (height == 0 && key.any { it.toInt() != 0 }) {
            // the network's first 2112 blocks are mined with a key of 32 zero bytes, as alone (Work.kt)
            throw PoolMisbehaves(tr("the pool named a RandomX key for block 0 that is not the network's", "pool uvedl pro blok 0 klíč RandomX, který není klíčem sítě"))
        }
        // back to an older key, even one named before, would be one more build: a pool could swing between two
        if (height < highest) {
            throw PoolMisbehaves(tr("the pool went back to the RandomX key of an older block ($height)", "pool se vrátil ke klíči RandomX staršího bloku ($height)"))
        }
        val known = byHeight[height]
        if (known != null) {
            if (known.contentEquals(key)) return false
            throw PoolMisbehaves(tr("the pool named two different RandomX keys for block $height", "pool uvedl pro blok $height dva různé klíče RandomX"))
        }
        val now = clock()
        if (highest >= 0 && changedAt != 0L && now - changedAt < CHANGE_MS) {
            throw PoolMisbehaves(tr("the pool changed the RandomX key twice within six hours; the network changes it about every three days",
                "pool změnil klíč RandomX dvakrát během šesti hodin; síť ho mění zhruba jednou za tři dny"))
        }
        if (highest >= 0) changedAt = now
        byHeight[height] = key.copyOf()
        highest = height
        while (byHeight.size > 4) byHeight.remove(byHeight.keys.min())
        return true
    }

    /** True when a job for [jobHeight] (-1: not known) is mined with the key of [keyHeight]. */
    fun fits(jobHeight: Int, keyHeight: Int): Boolean = jobHeight < 0 || Blocks.seedHeight(jobHeight) == keyHeight

    companion object {
        const val CHANGE_MS = 6 * 3600_000L
    }
}

/** What a pool says, as the client reads it. Called on the client's reading thread: quick, no waiting. */
interface StratumEvents {
    fun difficulty(d: Double)
    fun seed(key: ByteArray, height: Int)
    fun job(job: StratumJob)
    fun message(text: String)
    fun share(accepted: Boolean, why: String?)
}

/**
 * One connection to a pool. [run] connects, subscribes, authorizes and then reads until the connection ends,
 * which it always does with an exception that says why. [submit] and [close] may be called from other threads.
 */
class StratumClient(
    private val url: StratumUrl,
    private val user: String,
    private val password: String,
    private val agent: String,
    private val events: StratumEvents,
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private val writing = Any()
    private val ids = AtomicInteger(2)
    private val pending = ConcurrentHashMap<Int, Long>()
    @Volatile var extranonce1: ByteArray = ByteArray(0)
        private set
    @Volatile var extranonce2Size: Int = 4
        private set
    @Volatile var authorized = false
        private set
    /** True once the pool has let the address mine on this connection; unlike [authorized], [close] keeps it. */
    @Volatile var wasAuthorized = false
        private set
    @Volatile private var closed = false
    /** The newest job that came before the address was let in; given once it is (review of 0.3.0, L2). */
    private var held: StratumJob? = null

    fun run() {
        val s = Socket()
        socket = s
        if (closed) { s.close(); throw IOException("closed") }
        try {
            s.connect(InetSocketAddress(url.host, url.port), CONNECT_MS)
            // until the address is let in, an answer is waited for briefly; then the pool may be quiet for longer
            s.soTimeout = ANSWER_MS
            s.tcpNoDelay = true
            s.keepAlive = true
            val input = BufferedInputStream(s.getInputStream())
            out = s.getOutputStream()
            send(JSONObject().put("id", 1).put("method", "mining.subscribe").put("params", JSONArray().put(agent)))
            subscribed(answer(input, 1))
            send(JSONObject().put("id", 2).put("method", "mining.authorize").put("params", JSONArray().put(user).put(password)))
            val auth = answer(input, 2)
            if (auth.opt("result") != true) {
                throw StratumError(tr("the pool did not let this address mine: ", "pool tuto adresu těžit nenechal: ") + reason(auth.opt("error")))
            }
            authorized = true
            wasAuthorized = true
            s.soTimeout = READ_MS
            held?.let { held = null; events.job(it) }
            while (true) handle(line(input))
        } finally {
            close()
        }
    }

    private fun subscribed(ans: JSONObject) {
        val r = ans.opt("result") as? JSONArray
            ?: throw StratumError(tr("the pool did not take the subscription: ", "pool nepřijal přihlášení k odběru: ") + reason(ans.opt("error")))
        if (r.length() < 3) throw StratumError(tr("the pool answered the subscription in a form that is not known", "pool odpověděl na přihlášení k odběru neznámým tvarem"))
        val e1 = r.opt(1) as? String
        val size = (r.opt(2) as? Number)?.toInt()
        if (e1 == null || e1.length > 32 || size == null || size !in 1..8) {
            throw StratumError(tr("the pool answered the subscription in a form that is not known", "pool odpověděl na přihlášení k odběru neznámým tvarem"))
        }
        extranonce1 = try { Hex.decode(e1) } catch (e: IllegalArgumentException) {
            throw StratumError(tr("the pool sent an extranonce1 that is not hexadecimal", "pool poslal extranonce1, které není šestnáctkové"))
        }
        extranonce2Size = size
    }

    /**
     * Lines until the answer to [id]; what the pool says meanwhile is handled as it comes. The answer has
     * [ANSWER_MS] to come, however much else the pool sends meanwhile.
     */
    private fun answer(input: InputStream, id: Int): JSONObject {
        val until = System.currentTimeMillis() + ANSWER_MS
        while (true) {
            val said = parse(line(input))
            if (said.has("method") && !said.isNull("method")) dispatch(said)
            else if (said.optInt("id", -1) == id) return said
            if (System.currentTimeMillis() > until) throw StratumError(tr("the pool did not answer in time", "pool neodpověděl včas"))
        }
    }

    private fun parse(line: String): JSONObject {
        val notStratum = StratumError(tr("the pool sent something that is not stratum", "pool poslal něco, co není stratum"))
        if (!JsonDepth.fits(line)) throw StratumError(tr("the pool sent too deeply nested a line", "pool poslal příliš hluboko vnořený řádek"))
        return try { JSONObject(line) } catch (e: Exception) { throw notStratum } catch (e: StackOverflowError) { throw notStratum }
    }

    private fun handle(line: String) {
        val said = parse(line)
        if (said.has("method") && !said.isNull("method")) { dispatch(said); return }
        val id = said.optInt("id", -1)
        if (pending.remove(id) != null) {
            if (said.opt("result") == true) events.share(true, null) else events.share(false, reason(said.opt("error")))
        }
    }

    private fun dispatch(said: JSONObject) {
        val params = said.optJSONArray("params") ?: JSONArray()
        when (said.optString("method")) {
            "mining.set_difficulty" -> {
                val d = (params.opt(0) as? Number)?.toDouble() ?: throw StratumError(tr("the pool sent a difficulty that cannot be used", "pool poslal nepoužitelnou obtížnost"))
                if (!d.isFinite() || d <= 0.0) throw StratumError(tr("the pool sent a difficulty that cannot be used", "pool poslal nepoužitelnou obtížnost"))
                events.difficulty(d)
            }
            "mining.set_randomx_seed" -> {
                val key = params.opt(0) as? String
                val height = (params.opt(1) as? Number)?.toLong() ?: -1L
                if (key == null || key.length != 64 || !key.all { Character.digit(it, 16) >= 0 }) {
                    throw StratumError(tr("the pool sent a RandomX key that cannot be used", "pool poslal nepoužitelný klíč RandomX"))
                }
                events.seed(Hex.reversed(key.lowercase(Locale.ROOT)), if (height in 0..Int.MAX_VALUE.toLong()) height.toInt() else -1)
            }
            "mining.notify" -> {
                if (extranonce1.isEmpty()) return          // work before the subscription is answered cannot be used
                val job = StratumJob.parse(params)
                // work before the address is let in is kept, not mined: nothing found for it could be sent
                if (authorized) events.job(job) else held = job
            }
            "client.show_message" -> events.message(plain(params.opt(0)?.toString() ?: ""))
            else -> {}                                      // anything else is not needed to mine
        }
    }

    /** One share; false when it could not be written. The answer comes later through [StratumEvents.share]. */
    fun submit(job: StratumJob, extranonce2: ByteArray, nonce: Long): Boolean {
        if (!authorized) return false
        val id = ids.incrementAndGet()
        val now = System.currentTimeMillis()
        if (pending.size >= MOST_PENDING) pending.entries.removeIf { now - it.value > 120_000 }
        if (pending.size >= MOST_PENDING) return false
        pending[id] = now
        return try {
            send(JSONObject().put("id", id).put("method", "mining.submit").put("params", JSONArray()
                .put(user).put(job.id).put(Hex.encode(extranonce2)).put(job.time).put(StratumMath.nonceHex(nonce))))
            true
        } catch (e: IOException) {
            pending.remove(id)
            false
        }
    }

    private fun send(message: JSONObject) {
        val o = out ?: throw IOException("not connected")
        synchronized(writing) {
            o.write((message.toString() + "\n").toByteArray(Charsets.UTF_8))
            o.flush()
        }
    }

    /** Ends the connection; [run] then ends with an exception. */
    fun close() {
        closed = true
        authorized = false
        try { socket?.close() } catch (e: Exception) { }
    }

    /** One line of at most MOST_LINE bytes, without its end. */
    private fun line(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException(tr("the pool closed the connection", "pool ukončil spojení"))
            if (b == '\n'.code) {
                val s = buf.toString("UTF-8").trim()
                if (s.isEmpty()) { buf.reset(); continue }      // blank lines do not add up to one too long
                return s
            }
            buf.write(b)
            if (buf.size() > MOST_LINE) throw StratumError(tr("the pool sent too long a line", "pool poslal příliš dlouhý řádek"))
        }
    }

    companion object {
        const val CONNECT_MS = 15_000
        const val ANSWER_MS = 30_000            // for the answers to the subscription and to the address
        const val READ_MS = 300_000             // a pool sends new work every minute or so; five quiet minutes end it
        const val MOST_LINE = 64 * 1024
        const val MOST_PENDING = 256

        /** What a pool's error says, short and in plain letters, for the screen. */
        fun reason(error: Any?): String {
            val text = when (error) {
                null, JSONObject.NULL -> "?"
                is JSONArray -> listOfNotNull(error.opt(0)?.toString(), error.opt(1)?.toString()).joinToString(": ")
                is JSONObject -> listOfNotNull(error.opt("code")?.toString(), error.opt("message")?.toString()).joinToString(": ")
                else -> error.toString()
            }
            return plain(text).ifEmpty { "?" }
        }

        /**
         * Text from a pool, for the log: printable ASCII only, at most 200 characters, as the node's words are kept
         * (NodeClient). No line or paragraph separator, no change of the writing direction, nothing invisible
         * (review of 0.3.0, L4).
         */
        fun plain(s: String): String = s.filter { it in ' '..'~' }.take(200)
    }
}
