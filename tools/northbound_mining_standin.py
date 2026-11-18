#!/usr/bin/env python3
"""A stand-in for the mining routes of a Northbound wallet server, for tests on regtest only.

Written from server/MINING.md of github.com/nova9/northbound (commit 6cca3a6). It passes three questions
to a node's RPC: GET /v1/mining/template[?longpollid=], GET /v1/mining/block-hash?height=N and
POST /v1/mining/blocks {"block_hex": ...}. It is not that server: no limits, no cache, one thread a request.

usage: northbound_mining_standin.py PORT RPC_URL RPC_USER RPC_PASSWORD_FILE
"""
import base64, json, sys, time, urllib.request, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT, RPC, USER, PWFILE = int(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
AUTH = "Basic " + base64.b64encode(("%s:%s" % (USER, open(PWFILE).read().strip())).encode()).decode()

def rpc(method, params):
    req = urllib.request.Request(RPC, data=json.dumps({"jsonrpc": "1.0", "id": 1, "method": method, "params": params}).encode(),
                                 headers={"Authorization": AUTH, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            ans = json.load(r)
    except urllib.error.HTTPError as e:
        ans = json.load(e)
    if ans.get("error"):
        raise ValueError(str(ans["error"].get("message", ans["error"]))[:200])
    return ans["result"]

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, fmt, *a):
        sys.stdout.write("%s %s\n" % (time.strftime("%H:%M:%S"), fmt % a)); sys.stdout.flush()
    def out(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def do_GET(self):
        u = urllib.parse.urlsplit(self.path)
        q = urllib.parse.parse_qs(u.query, keep_blank_values=True)
        try:
            if u.path == "/v1/mining/template":
                if set(q) - {"longpollid"}:
                    return self.out(400, {"message": "unknown query parameter"})
                t = rpc("getblocktemplate", [{"rules": ["segwit"]}])
                want = q.get("longpollid", [None])[0]
                end = time.time() + 25
                while want is not None and t.get("longpollid") == want and time.time() < end:
                    time.sleep(1)
                    t = rpc("getblocktemplate", [{"rules": ["segwit"]}])
                return self.out(200, t)
            if u.path == "/v1/mining/block-hash":
                if set(q) != {"height"} or not q["height"][0].isdigit():
                    return self.out(400, {"message": "height must be an integer"})
                h = int(q["height"][0])
                try:
                    return self.out(200, {"height": h, "hash": rpc("getblockhash", [h])})
                except ValueError as e:
                    return self.out(400, {"message": str(e)})
            return self.out(404, {"message": "not found"})
        except Exception as e:
            return self.out(502, {"message": "upstream: %s" % type(e).__name__})
    def do_POST(self):
        if urllib.parse.urlsplit(self.path).path != "/v1/mining/blocks":
            return self.out(404, {"message": "not found"})
        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n > 8_001_024:
                return self.out(413, {"message": "too large"})
            body = json.loads(self.rfile.read(n))
            if set(body) != {"block_hex"} or not isinstance(body["block_hex"], str):
                return self.out(422, {"message": "block_hex expected"})
            bytes.fromhex(body["block_hex"])
        except Exception:
            return self.out(400, {"message": "malformed"})
        try:
            r = rpc("submitblock", [body["block_hex"]])
        except Exception as e:
            return self.out(502, {"message": "upstream: %s" % type(e).__name__})
        if r is None: st = "accepted"
        elif r == "duplicate": st = "duplicate"
        elif r in ("inconclusive", "duplicate-inconclusive"): st = "inconclusive"
        else: st = "rejected"
        return self.out(200, {"status": st, "result": r})

ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
