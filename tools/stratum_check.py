#!/usr/bin/env python3
"""Polarka Miner's stratum client against the pool's own stratum code (polarka_agent/stratum.py).

The client is the app's own Stratum.kt, run on a computer by tools/stratum-jvm (no Android in that file). The node
and the proof of work are made up as in the pool's own tests (SHA-256 of the key and the header in place of
RandomX), so what this checks is everything around the hash: the byte orders of the header, the share target of a
difficulty, shares taken and paid, a new tip, a new RandomX key, a new difficulty and a connection that ends.

    gradle -p tools/stratum-jvm installDist
    POLARKA_AGENT=/path/to/polarka-agent python3 tools/stratum_check.py tools/stratum-jvm/build/install/stratum-jvm/bin/stratum-jvm

POLARKA_AGENT is a checkout of polarka-agent (its tests/test_pool.py holds the made-up node and hash).
"""
import json
import os
import queue
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time

sys.path.insert(0, os.environ["POLARKA_AGENT"])
from polarka_agent import pool, stratum                          # noqa: E402
from tests.test_pool import ALICE, PAY, FakeHash, FakeNode         # noqa: E402


SPAN = (16, 29)      # difficulties 2^-16 to 2^-29: on the made-up chain the bounds would leave only the easiest


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class Run:
    def __init__(self, harness):
        self.harness = harness
        self.events, self.seen = queue.Queue(), []

    def read(self, proc):
        for line in proc.stdout:
            line = line.strip()
            if line.startswith("{"):
                self.events.put(json.loads(line))

    def wait_for(self, pred, what, timeout=60):
        end = time.time() + timeout
        while time.time() < end:
            try:
                e = self.events.get(timeout=0.5)
            except queue.Empty:
                continue
            self.seen.append(e)
            if pred(e):
                return e
        raise AssertionError("waited in vain for %s; the last events: %s" % (what, self.seen[-10:]))

    def accepted(self, n, what):
        count = [0]

        def pred(e):
            if e.get("ev") == "share" and e["accepted"]:
                count[0] += 1
            return count[0] >= n
        self.wait_for(pred, what)


def check_targets(harness):
    out = subprocess.run([harness, "--targets"], capture_output=True, text=True, timeout=60, check=True).stdout
    said = json.loads([l for l in out.splitlines() if l.startswith("{")][-1])
    for k, hexed in said["targets"]:
        assert int(hexed, 16) == stratum.share_target(k), "the target of 2^-%d differs from the pool's" % k
    print("ok: the share targets of 2^-k for k from -16 to 31 are the pool's")


def main(harness):
    check_targets(harness)
    folder = tempfile.mkdtemp(prefix="miner-stratum-check-")
    node = FakeNode()           # on a made-up chain this easy many shares are blocks too: the tip moves often
    p = pool.Pool(node, "regtest", PAY, folder, ratio=16, fee_percent=5, hasher=FakeHash())
    port = free_port()
    server = stratum.Stratum(p, "127.0.0.1:%d" % port, welcome="hello {address}", span=SPAN)
    server.start()
    r = Run(harness)
    proc = subprocess.Popen([harness, "127.0.0.1", str(port), ALICE], stdout=subprocess.PIPE, text=True, bufsize=1)
    threading.Thread(target=r.read, args=(proc,), daemon=True).start()
    try:
        r.wait_for(lambda e: e.get("ev") == "message" and e["text"] == "hello " + ALICE, "the welcome")
        first = r.wait_for(lambda e: e.get("ev") == "job", "the first job")
        assert first["clean"] and first["height"] == node.height, first
        r.accepted(5, "five shares")
        print("ok: subscribed, authorized, five shares accepted at difficulty %s" % first["d"])

        r.wait_for(lambda e: e.get("ev") == "job" and e["d"] != first["d"], "a job at a new difficulty")
        r.accepted(3, "shares at the new difficulty")
        print("ok: the pool moved the difficulty and shares at the new one were accepted")

        before = node.height
        r.wait_for(lambda e: e.get("ev") == "job" and e["clean"] and e["height"] > before, "a clean job on a new tip")
        r.accepted(3, "shares on the new tip")
        print("ok: new tips (blocks found by the shares), clean jobs up to height %d, shares accepted" % node.height)

        node.height, node.previous = 4160, "33" * 32           # past 4096 + 64: the key is now the hash of block 4096
        p.tip_seen = None
        r.wait_for(lambda e: e.get("ev") == "seed" and e["height"] == 4096, "the new RandomX key")
        r.accepted(3, "shares with the new key")
        print("ok: a new RandomX key (block 4096), shares accepted")

        server.stop()
        r.wait_for(lambda e: e.get("ev") == "closed", "the connection to end")
        server = stratum.Stratum(p, "127.0.0.1:%d" % port, welcome="hello {address}", span=SPAN)
        server.start()
        r.wait_for(lambda e: e.get("ev") == "message" and e["text"] == "hello " + ALICE, "the welcome after connecting again", timeout=30)
        r.accepted(3, "shares after connecting again")
        print("ok: the pool went away and came back; connected again, shares accepted")
    finally:
        proc.kill()
        proc.wait()
        server.stop()
    time.sleep(0.5)
    with open(os.path.join(folder, "pool-shares.jsonl")) as f:
        ledger = [json.loads(line) for line in f]
    taken = [e for e in ledger if e["address"] == ALICE and e["via"] == "stratum"]
    accepted = [e for e in r.seen if e.get("ev") == "share" and e["accepted"]]
    refused = [e for e in r.seen if e.get("ev") == "share" and not e["accepted"]]
    assert len(taken) >= len(accepted), (len(taken), len(accepted))
    # The only refusal there may be: a share for a tip that had just moved (21). Anything else, above all a hash
    # above its target (23), would mean that the phone hashes another header than the pool checks.
    wrong = [e for e in refused if not str(e["why"]).startswith("21")]
    assert not wrong, wrong
    shutil.rmtree(folder, ignore_errors=True)
    print("ok: %d shares in the pool's ledger for %s over stratum, %d accepted as the client saw it, %d refused as stale"
          % (len(taken), ALICE, len(accepted), len(refused)))


if __name__ == "__main__":
    main(sys.argv[1])
