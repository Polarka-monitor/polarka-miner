#!/usr/bin/env python3
"""A second, independent way from a block template to a block, written from the reference miner
(src/truenorth/miner.cpp) for checking the app's own block assembly. It does not compute RandomX: on regtest
about every second nonce passes, so it tries nonces until the node accepts the block. Never for mainnet.

usage: gbt_reference.py --rpc-url http://127.0.0.1:18591 --cookie <file> --address rnorth1z... --blocks 3 [--dump dir]
"""
import argparse, base64, hashlib, json, os, sys, urllib.request

CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
BECH32M = 0x2bc830a3


def polymod(values):
    gen = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
    chk = 1
    for v in values:
        b = chk >> 25
        chk = (chk & 0x1ffffff) << 5 ^ v
        for i in range(5):
            chk ^= gen[i] if ((b >> i) & 1) else 0
    return chk


def address_script(addr):
    """north1z... / rnorth1z... -> OP_2 <32 bytes>. Only witness version 2 with 32 bytes is taken."""
    if addr != addr.lower() or "1" not in addr:
        raise ValueError("address: case or separator")
    pos = addr.rfind("1")
    hrp, data = addr[:pos], addr[pos + 1:]
    if any(c not in CHARSET for c in data) or len(data) < 7:
        raise ValueError("address: characters")
    vals = [CHARSET.find(c) for c in data]
    exp = [ord(c) >> 5 for c in hrp] + [0] + [ord(c) & 31 for c in hrp]
    if polymod(exp + vals) != BECH32M:
        raise ValueError("address: checksum")
    ver, prog5 = vals[0], vals[1:-6]
    acc = bits = 0
    out = bytearray()
    for v in prog5:
        acc = (acc << 5) | v
        bits += 5
        if bits >= 8:
            bits -= 8
            out.append((acc >> bits) & 0xff)
    if bits >= 5 or (acc & ((1 << bits) - 1)):
        raise ValueError("address: padding")
    if ver != 2 or len(out) != 32:
        raise ValueError("address: not a version 2 address of 32 bytes")
    return hrp, bytes([0x52, 0x20]) + bytes(out)


def dsha(b):
    return hashlib.sha256(hashlib.sha256(b).digest()).digest()


def varint(n):
    if n < 0xfd:
        return bytes([n])
    if n <= 0xffff:
        return b"\xfd" + n.to_bytes(2, "little")
    if n <= 0xffffffff:
        return b"\xfe" + n.to_bytes(4, "little")
    return b"\xff" + n.to_bytes(8, "little")


def push(data):
    """A direct push the way CScript does it for byte vectors (only sizes below 76 are needed here)."""
    assert len(data) < 76
    return bytes([len(data)]) + data


def height_push(height):
    """CScript() << int64: OP_0, OP_1..OP_16, otherwise the shortest little-endian number with a sign bit."""
    if height == 0:
        return b"\x00"
    if 1 <= height <= 16:
        return bytes([0x50 + height])
    out = bytearray()
    n = height
    while n:
        out.append(n & 0xff)
        n >>= 8
    if out[-1] & 0x80:
        out.append(0)
    return push(bytes(out))


def merkle_root(hashes):
    """Bitcoin's merkle root over 32-byte hashes in internal byte order."""
    layer = list(hashes)
    while len(layer) > 1:
        if len(layer) % 2:
            layer.append(layer[-1])
        layer = [dsha(layer[i] + layer[i + 1]) for i in range(0, len(layer), 2)]
    return layer[0]


def commitment_script(h):
    return bytes([0x6a, 0x24, 0xaa, 0x21, 0xa9, 0xed]) + h


def coinbase(height, value, pay, extranonce, commitment, with_witness):
    sig = height_push(height) + push(extranonce.to_bytes(8, "little"))
    outs = [value.to_bytes(8, "little") + varint(len(pay)) + pay]
    if commitment is not None:
        cs = commitment_script(commitment)
        outs.append((0).to_bytes(8, "little") + varint(len(cs)) + cs)
    body = b"\x01" + bytes(32) + b"\xff\xff\xff\xff" + varint(len(sig)) + sig + b"\xff\xff\xff\xff"
    body += varint(len(outs)) + b"".join(outs)
    ver, lock = (1).to_bytes(4, "little"), (0).to_bytes(4, "little")
    if with_witness and commitment is not None:
        return ver + b"\x00\x01" + body + b"\x01" + b"\x20" + bytes(32) + lock
    return ver + body + lock


def assemble(tmpl, pay, extranonce):
    """Returns (header without nonce: 76 bytes, the rest of the block after the header, target as int)."""
    height, value = tmpl["height"], tmpl["coinbasevalue"]
    txs = tmpl.get("transactions", [])
    segwit = "default_witness_commitment" in tmpl
    commitment = None
    if segwit:
        wroot = merkle_root([bytes(32)] + [bytes.fromhex(t["hash"])[::-1] for t in txs])
        commitment = dsha(wroot + bytes(32))
    cb_plain = coinbase(height, value, pay, extranonce, commitment, False)
    cb_full = coinbase(height, value, pay, extranonce, commitment, True)
    root = merkle_root([dsha(cb_plain)] + [bytes.fromhex(t["txid"])[::-1] for t in txs])
    header = (tmpl["version"].to_bytes(4, "little") + bytes.fromhex(tmpl["previousblockhash"])[::-1] + root
              + tmpl["curtime"].to_bytes(4, "little") + bytes.fromhex(tmpl["bits"])[::-1])
    assert len(header) == 76
    rest = varint(1 + len(txs)) + cb_full + b"".join(bytes.fromhex(t["data"]) for t in txs)
    bits = int(tmpl["bits"], 16)
    target = (bits & 0x7fffff) << (8 * ((bits >> 24) - 3))
    return header, rest, target, commitment


class Node:
    def __init__(self, url, cookie):
        self.url = url
        self.auth = base64.b64encode(open(cookie).read().strip().encode()).decode()
        self.n = 0

    def call(self, method, *params):
        self.n += 1
        req = urllib.request.Request(self.url, json.dumps({"jsonrpc": "1.0", "id": self.n, "method": method, "params": list(params)}).encode(),
                                     {"Authorization": "Basic " + self.auth, "Content-Type": "application/json"})
        try:
            body = urllib.request.urlopen(req, timeout=120).read()
        except urllib.error.HTTPError as e:
            body = e.read()
        ans = json.loads(body)
        if ans.get("error"):
            raise RuntimeError("%s: %s" % (method, ans["error"]))
        return ans["result"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rpc-url", required=True)
    ap.add_argument("--cookie", required=True)
    ap.add_argument("--address", required=True)
    ap.add_argument("--blocks", type=int, default=1)
    ap.add_argument("--dump", help="folder for vectors: template, extranonce, nonce and block of every block made")
    ap.add_argument("--quiet", action="store_true")
    a = ap.parse_args()
    node = Node(a.rpc_url, a.cookie)
    chain = node.call("getblockchaininfo")["chain"]
    if chain != "regtest":
        sys.exit("this tool guesses nonces and is for regtest only; the node says: " + chain)
    hrp, pay = address_script(a.address)
    made = 0
    while made < a.blocks:
        tmpl = node.call("getblocktemplate", {"rules": ["segwit"]})
        extranonce = made
        header, rest, target, commitment = assemble(tmpl, pay, extranonce)
        if commitment is not None and not tmpl.get("transactions"):
            pass  # with no transactions the node's own default commitment must be the same as ours
        if commitment is not None and commitment_script(commitment).hex() != tmpl["default_witness_commitment"]:
            sys.exit("witness commitment differs from the node's default_witness_commitment")
        for nonce in range(0, 4000):
            block = header + nonce.to_bytes(4, "little") + rest
            res = node.call("submitblock", block.hex())
            if res is None:
                break
            if res != "high-hash":
                sys.exit("block refused at height %d: %s" % (tmpl["height"], res))
        else:
            sys.exit("no nonce passed in 4000 tries")
        made += 1
        best = node.call("getbestblockhash")
        if not a.quiet:
            print("height %d accepted, nonce %d, %d transactions, tip %s" % (tmpl["height"], nonce, len(tmpl.get("transactions", [])), best[:16]))
        if a.dump:
            os.makedirs(a.dump, exist_ok=True)
            with open(os.path.join(a.dump, "block-%d.json" % tmpl["height"]), "w") as f:
                json.dump({"template": tmpl, "pay_script": pay.hex(), "extranonce": extranonce, "nonce": nonce,
                           "header76": header.hex(), "block": block.hex(), "block_hash_tip": best}, f)
    print("made %d blocks, height now %d" % (made, node.call("getblockcount")))


if __name__ == "__main__":
    main()
