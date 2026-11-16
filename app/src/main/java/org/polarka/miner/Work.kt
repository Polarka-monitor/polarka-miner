package org.polarka.miner

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Bytes to and from lowercase hex. */
object Hex {
    fun decode(s: String): ByteArray {
        require(s.length % 2 == 0) { "hex: odd length" }
        return ByteArray(s.length / 2) {
            val hi = Character.digit(s[2 * it], 16)
            val lo = Character.digit(s[2 * it + 1], 16)
            require(hi >= 0 && lo >= 0) { "hex: not a hex digit" }
            ((hi shl 4) or lo).toByte()
        }
    }

    fun encode(b: ByteArray): String {
        val d = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) { sb.append(d[(x.toInt() shr 4) and 15]); sb.append(d[x.toInt() and 15]) }
        return sb.toString()
    }

    /** A hash as the node prints it (most significant byte first) to the order it has inside a block. */
    fun reversed(s: String): ByteArray = decode(s).reversedArray()
}

/**
 * One piece of work made from a block template: everything of the block except the nonce.
 * [target] is a 256-bit number, least significant byte first, as the native side compares it.
 */
class Work(
    val height: Int,
    val header76: ByteArray,
    val rest: ByteArray,
    val target: ByteArray,
    val seedHeight: Int,
    val previous: String,
    val longPollId: String?,
    val transactions: Int,
) {
    fun header(nonce: Long): ByteArray = header76 + Blocks.le(nonce, 4)
    fun block(nonce: Long): ByteArray = header(nonce) + rest
}

/**
 * From a block template to a block, the way the reference miner of the project does it
 * (src/truenorth/miner.cpp): a version 1 coinbase whose input script is the height (BIP34) and eight bytes
 * of extranonce, the reward to one address, the witness commitment (BIP141), the two merkle roots and the
 * 80-byte header. The ids of the template's transactions are taken from the template. No keys anywhere.
 */
object Blocks {
    const val EPOCH = 2048
    const val LAG = 64
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32M = 0x2bc830a3
    val PREFIXES = mapOf("north" to tr("mainnet", "hlavní síť"), "tnorth" to "testnet", "tnorth4" to "testnet4", "snorth" to "signet", "rnorth" to "regtest")

    /** The height of the block whose hash is the RandomX key for the block at [next]; 0 means the key of all zeros. */
    fun seedHeight(next: Int): Int = if (next < EPOCH + LAG) 0 else ((next - LAG) / EPOCH) * EPOCH

    fun le(v: Long, bytes: Int): ByteArray = ByteArray(bytes) { ((v ushr (8 * it)) and 0xff).toByte() }

    fun dsha(b: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(b))
    }

    fun varint(n: Int): ByteArray = when {
        n < 0xfd -> byteArrayOf(n.toByte())
        n <= 0xffff -> byteArrayOf(0xfd.toByte()) + le(n.toLong(), 2)
        else -> byteArrayOf(0xfe.toByte()) + le(n.toLong(), 4)
    }

    private fun push(data: ByteArray): ByteArray {
        require(data.size < 76) { "push: too long" }
        return byteArrayOf(data.size.toByte()) + data
    }

    /** CScript() << int64: OP_0, OP_1..OP_16, otherwise the shortest little-endian number with a sign bit. */
    fun heightPush(height: Int): ByteArray {
        require(height >= 0) { "height below zero" }
        if (height == 0) return byteArrayOf(0)
        if (height <= 16) return byteArrayOf((0x50 + height).toByte())
        val out = ByteArrayOutputStream()
        var n = height
        var last = 0
        while (n != 0) { last = n and 0xff; out.write(last); n = n ushr 8 }
        if (last and 0x80 != 0) out.write(0)
        return push(out.toByteArray())
    }

    fun merkleRoot(hashes: List<ByteArray>): ByteArray {
        require(hashes.isNotEmpty()) { "merkle: nothing to hash" }
        var layer = hashes
        while (layer.size > 1) {
            val odd = if (layer.size % 2 == 1) layer + listOf(layer.last()) else layer
            layer = (0 until odd.size step 2).map { dsha(odd[it] + odd[it + 1]) }
        }
        return layer[0]
    }

    private fun polymod(values: List<Int>): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((b ushr i) and 1 == 1) chk = chk xor gen[i]
        }
        return chk
    }

    /**
     * north1z... (or the same form of a test network) to its output script: OP_2 and 32 bytes.
     * Returns the prefix and the script. Anything else is refused with a reason.
     */
    fun addressScript(input: String): Pair<String, ByteArray> {
        var addr = input.trim()
        if (addr == addr.uppercase()) addr = addr.lowercase()
        require(addr == addr.lowercase()) { tr("the address mixes upper and lower case", "adresa míchá velká a malá písmena") }
        val pos = addr.lastIndexOf('1')
        require(pos >= 1 && addr.length - pos - 1 >= 7) { tr("the address does not have the right form", "adresa nemá správný tvar") }
        val hrp = addr.substring(0, pos)
        require(hrp in PREFIXES) { tr("the address does not start with north1 or with a test network's prefix", "adresa nezačíná north1 ani předponou zkušební sítě") }
        val vals = addr.substring(pos + 1).map { CHARSET.indexOf(it) }
        require(vals.all { it >= 0 }) { tr("the address has a character that does not belong in it", "adresa obsahuje znak, který do ní nepatří") }
        val exp = hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }
        require(polymod(exp + vals) == BECH32M) { tr("the address has a wrong checksum (a typo?)", "adresa má špatný kontrolní součet (překlep?)") }
        val version = vals[0]
        val out = ByteArrayOutputStream()
        var acc = 0
        var bits = 0
        for (v in vals.subList(1, vals.size - 6)) {
            acc = ((acc shl 5) or v) and 0xfff
            bits += 5
            if (bits >= 8) { bits -= 8; out.write((acc ushr bits) and 0xff) }
        }
        require(bits < 5 && (acc and ((1 shl bits) - 1)) == 0) { tr("the address ends wrongly", "adresa má špatné zakončení") }
        val program = out.toByteArray()
        require(version == 2 && program.size == 32) { tr("this is not a north1z address (version 2, 32 bytes)", "adresa není typu north1z (verze 2, 32 bajtů)") }
        return hrp to (byteArrayOf(0x52, 0x20) + program)
    }

    fun commitmentScript(h: ByteArray): ByteArray =
        byteArrayOf(0x6a, 0x24, 0xaa.toByte(), 0x21, 0xa9.toByte(), 0xed.toByte()) + h

    private fun coinbase(height: Int, value: Long, pay: ByteArray, extranonce: Long, commitment: ByteArray?, withWitness: Boolean): ByteArray {
        val sig = heightPush(height) + push(le(extranonce, 8))
        val o = ByteArrayOutputStream()
        o.write(le(1, 4))                                   // version 1
        if (withWitness && commitment != null) o.write(byteArrayOf(0, 1))
        o.write(1)                                          // one input
        o.write(ByteArray(32)); o.write(le(0xffffffffL, 4)) // the null previous output
        o.write(varint(sig.size)); o.write(sig)
        o.write(le(0xffffffffL, 4))                         // sequence
        o.write(if (commitment != null) 2 else 1)           // outputs
        o.write(le(value, 8)); o.write(varint(pay.size)); o.write(pay)
        if (commitment != null) {
            val cs = commitmentScript(commitment)
            o.write(le(0, 8)); o.write(varint(cs.size)); o.write(cs)
        }
        if (withWitness && commitment != null) { o.write(1); o.write(0x20); o.write(ByteArray(32)) }
        o.write(le(0, 4))                                   // lock time
        return o.toByteArray()
    }

    /** The compact form of the target ("bits") to 32 bytes, least significant first. */
    fun targetFromBits(bits: Long): ByteArray {
        val exp = (bits ushr 24).toInt()
        val mant = bits and 0x7fffff
        require(bits and 0x800000L == 0L && mant != 0L && exp in 3..32) { tr("the template has a target that cannot be used", "šablona má nepoužitelný cíl") }
        val t = ByteArray(32)
        for (i in 0 until 3) {
            val at = exp - 3 + i
            val b = ((mant ushr (8 * i)) and 0xff).toInt()
            if (at < 32) t[at] = b.toByte() else require(b == 0) { tr("the template has a target that cannot be used", "šablona má nepoužitelný cíl") }
        }
        return t
    }

    fun assemble(t: JSONObject, pay: ByteArray, extranonce: Long): Work {
        val height = t.getInt("height")
        val txs = t.optJSONArray("transactions")
        val count = txs?.length() ?: 0
        var commitment: ByteArray? = null
        if (t.has("default_witness_commitment")) {
            val wtx = listOf(ByteArray(32)) + (0 until count).map { Hex.reversed(txs!!.getJSONObject(it).getString("hash")) }
            commitment = dsha(merkleRoot(wtx) + ByteArray(32))
            check(Hex.encode(commitmentScript(commitment)) == t.getString("default_witness_commitment")) { tr("the witness commitment differs from the node's", "závazek svědků se liší od hodnoty uzlu") }
        }
        val plain = coinbase(height, t.getLong("coinbasevalue"), pay, extranonce, commitment, false)
        val full = coinbase(height, t.getLong("coinbasevalue"), pay, extranonce, commitment, true)
        val root = merkleRoot(listOf(dsha(plain)) + (0 until count).map { Hex.reversed(txs!!.getJSONObject(it).getString("txid")) })
        val bits = t.getString("bits")
        val header = le(t.getLong("version"), 4) + Hex.reversed(t.getString("previousblockhash")) + root +
            le(t.getLong("curtime"), 4) + Hex.reversed(bits)
        check(header.size == 76) { tr("the header is not 76 bytes", "hlavička nemá 76 bajtů") }
        val target = targetFromBits(bits.toLong(16))
        if (t.has("target")) check(Hex.encode(target.reversedArray()) == t.getString("target")) { tr("the target from bits differs from the template's target", "cíl z bits se liší od cíle v šabloně") }
        val rest = ByteArrayOutputStream()
        rest.write(varint(1 + count)); rest.write(full)
        for (i in 0 until count) rest.write(Hex.decode(txs!!.getJSONObject(i).getString("data")))
        return Work(height, header, rest.toByteArray(), target, seedHeight(height), t.getString("previousblockhash"),
            if (t.has("longpollid")) t.getString("longpollid") else null, count)
    }
}
