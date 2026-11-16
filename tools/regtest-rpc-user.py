#!/usr/bin/env python3
"""Gives the private regtest node a user for the miner: name "miner", a random test password, allowed to call
only getblocktemplate, getblockhash and submitblock. Writes truenorth.conf in the node's folder and keeps
the password in ~/north-build/regtest-miner/rpc-test.txt (a test value for regtest on this computer only).
Prints nothing secret. Restart the node afterwards."""
import hashlib, hmac, os, secrets
base = os.path.expanduser("~/north-build/regtest-miner")
password = secrets.token_urlsafe(18)
salt = secrets.token_hex(16)
digest = hmac.new(salt.encode(), password.encode(), hashlib.sha256).hexdigest()
conf = os.path.join(base, "node", "truenorth.conf")
with open(conf, "w") as f:
    f.write("# written by tools/regtest-rpc-user.py; regtest only\n[regtest]\n")
    f.write("rpcauth=miner:%s$%s\n" % (salt, digest))
    f.write("rpcwhitelist=miner:getblocktemplate,getblockhash,submitblock\n")
    f.write("rpcwhitelistdefault=0\n")
secret = os.path.join(base, "rpc-test.txt")
with open(secret, "w") as f:
    f.write(password + "\n")
os.chmod(secret, 0o600)
print("user miner written; password in rpc-test.txt (%d characters)" % len(password))
