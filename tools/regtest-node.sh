#!/bin/sh
# A private regtest node for trying the miner. It talks to nobody (no listening, no peers) and keeps its
# data in ~/north-build/regtest-miner. The program is a copy of the node under another name, so that tools
# usage: regtest-node.sh start | stop | status | cli <arguments for truenorth-cli>
set -eu
BIN="${TN_BIN:?set TN_BIN to the folder with truenorthd and truenorth-cli}"
BASE="$HOME/north-build/regtest-miner"
D="$BASE/node"
P=18591
NODE="$BASE/tn-regtest-node"
cli() { "$BIN/truenorth-cli" -datadir="$D" -chain=regtest -rpcconnect=127.0.0.1 -rpcport="$P" "$@"; }
case "${1:-}" in
  start)
    mkdir -p "$D"
    [ -x "$NODE" ] || cp "$BIN/truenorthd" "$NODE"
    if cli getblockcount >/dev/null 2>&1; then echo "already running"; exit 0; fi
    "$NODE" -datadir="$D" -chain=regtest -listen=0 -connect=0 -dnsseed=0 -fixedseeds=0 -server=1 \
      -rpcbind=127.0.0.1 -rpcallowip=127.0.0.1 -rpcport="$P" -printtoconsole=0 -daemonwait > "$BASE/node.out" 2>&1 \
      || { echo "node did not start"; tail -5 "$BASE/node.out"; exit 1; }
    echo "started, height $(cli getblockcount)"
    ;;
  stop) cli stop ;;
  status) cli getblockcount ;;
  cli) shift; cli "$@" ;;
  *) echo "usage: regtest-node.sh start | stop | status | cli ..."; exit 2 ;;
esac
