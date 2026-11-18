package org.polarka.miner

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The node said no, or said something that is not an answer. The message is fit for the screen. */
class NodeError(message: String) : Exception(message)

/**
 * The three questions the miner asks a TrueNorth node over its RPC: a block template, the hash of a block
 * at a height (for the RandomX key) and "here is a block". Two kinds of server answer them.
 *
 * With a name and a password it is one's own node and its RPC; the node's user can be limited to exactly
 * these three calls with rpcwhitelist. Name and password go in the Authorization header of every request;
 * on a plain http address they are readable to whoever can read the network between phone and node, so
 * the address should be one inside a private network (Tailscale, home network).
 *
 * Without a name and a password it is a wallet server with the mining routes of Northbound
 * (github.com/nova9/northbound, server/MINING.md). No Authorization header is sent at all.
 */
class NodeClient(url: String, user: String, password: String) {
    /** True for a wallet server with the Northbound mining routes, false for a node's RPC. */
    val rest = user.isEmpty() && password.isEmpty()
    private val url = if (rest) url.trimEnd('/') else url
    private val auth = if (rest) null
        else "Basic " + Base64.encodeToString("$user:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    @Volatile private var waiting: HttpURLConnection? = null
    private var id = 0

    private fun text(c: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        return stream?.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(65536)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > 16 * 1024 * 1024) throw NodeError(tr("the node's answer is too large", "odpověď uzlu je příliš velká"))
            }
            out.toString("UTF-8")
        } ?: ""
    }

    /** One request to a wallet server: a JSON object comes back, or a NodeError with the server's reason. */
    private fun ask(path: String, body: JSONObject?, readTimeoutMs: Int, interruptible: Boolean): JSONObject {
        val c = URL(url + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("Accept", "application/json")
            if (interruptible) waiting = c
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = text(c, code)
            if (code == 429) throw NodeError(tr("the server asks for fewer requests (429)", "server žádá méně dotazů (429)"))
            if (code == 404) throw NodeError(tr("nothing hands out work at this address (404): check the server's address, or the server has no mining routes", "na této adrese nikdo práci nerozdává (404): zkontroluj adresu serveru, nebo server těžební trasy nemá"))
            val ans = try { JSONObject(text) } catch (e: Exception) { null }
            if (code !in 200..299) {
                // the server's own words, for the owner's eyes: short and without anything but plain letters
                val why = (ans?.optString("message") ?: "").filter { it in ' '..'~' }.take(160)
                throw NodeError("server: " + (if (why.isEmpty()) "HTTP $code" else "$why (HTTP $code)"))
            }
            return ans ?: throw NodeError(tr("an answer from the server that was not expected (HTTP $code)", "server odpověděl nečekaně (HTTP $code)"))
        } finally {
            if (interruptible) waiting = null
            c.disconnect()
        }
    }

    private fun call(method: String, params: JSONArray, readTimeoutMs: Int, interruptible: Boolean): Any? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = readTimeoutMs
            c.requestMethod = "POST"
            c.doOutput = true
            if (auth != null) c.setRequestProperty("Authorization", auth)
            c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("jsonrpc", "1.0").put("id", ++id).put("method", method).put("params", params)
            if (interruptible) waiting = c
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code == 429) throw NodeError(tr("the server asks for fewer requests (429)", "server žádá méně dotazů (429)"))
            if (code == 401) throw NodeError(tr("the node refused the name or the password (401)", "uzel odmítl jméno nebo heslo (401)"))
            if (code == 403) throw NodeError(tr("the node does not allow this user that call ($method, 403)", "uzel toto volání uživateli nedovoluje ($method, 403)"))
            val text = text(c, code)
            val ans = try { JSONObject(text) } catch (e: Exception) { throw NodeError(tr("an answer from the node that was not expected (HTTP $code)", "uzel odpověděl nečekaně (HTTP $code)")) }
            if (!ans.isNull("error")) {
                val err = ans.optJSONObject("error")
                throw NodeError(tr("node: ", "uzel: ") + (err?.optString("message") ?: ans.get("error").toString()).take(200))
            }
            return ans.opt("result")
        } finally {
            if (interruptible) waiting = null
            c.disconnect()
        }
    }

    /** A block template. With [longPollId] the node answers only when there is something new to mine on. */
    fun template(longPollId: String?): JSONObject {
        if (rest) {
            // a Northbound server ends a long poll after 25 seconds at the latest
            val q = if (longPollId != null) "?longpollid=" + URLEncoder.encode(longPollId, "UTF-8") else ""
            return ask("/v1/mining/template$q", null, if (longPollId != null) 45_000 else 30_000, longPollId != null)
        }
        val arg = JSONObject().put("rules", JSONArray().put("segwit"))
        if (longPollId != null) arg.put("capabilities", JSONArray().put("longpoll")).put("longpollid", longPollId)
        val res = call("getblocktemplate", JSONArray().put(arg), if (longPollId != null) 150_000 else 30_000, longPollId != null)
        return res as? JSONObject ?: throw NodeError(tr("the node gave no block template", "uzel nedal šablonu bloku"))
    }

    fun blockHash(height: Int): String {
        if (rest) {
            val h = ask("/v1/mining/block-hash?height=$height", null, 30_000, false).optString("hash")
            if (h.length != 64) throw NodeError(tr("the server gave a block hash in a wrong form", "server dal hash bloku ve špatném tvaru"))
            return h
        }
        val res = call("getblockhash", JSONArray().put(height), 30_000, false)
        val s = res as? String ?: throw NodeError(tr("the node gave no hash for block $height", "uzel nedal hash bloku $height"))
        if (s.length != 64) throw NodeError(tr("the node gave a block hash in a wrong form", "uzel dal hash bloku ve špatném tvaru"))
        return s
    }

    /** Null means the node took the block; otherwise the node's reason (high-hash, inconclusive, duplicate, ...). */
    fun submit(blockHex: String): String? {
        if (rest) {
            val ans = ask("/v1/mining/blocks", JSONObject().put("block_hex", blockHex), 45_000, false)
            if (ans.optString("status") == "accepted") return null
            val r = ans.opt("result")
            return (if (r == null || r == JSONObject.NULL) ans.optString("status", "?") else r.toString()).filter { it in ' '..'~' }.take(100)
        }
        val res = call("submitblock", JSONArray().put(blockHex), 30_000, false)
        return if (res == null || res == JSONObject.NULL) null else res.toString()
    }

    /** Ends a waiting long poll from another thread; the waiting call then fails with an IOException. */
    fun interrupt() {
        try { waiting?.disconnect() } catch (e: Exception) { }
    }
}
