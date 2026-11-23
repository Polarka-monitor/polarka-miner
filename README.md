# Polarka Miner

An Android app that mines [TrueNorth](https://github.com/truenorth-project/truenorth) (NORTH) on a phone: in a pool over stratum, or solo through a wallet server that hands out work, or against a node you run yourself.

**Status: test build.** It has mined on regtest and against a node on mainnet on one phone. From 0.2.0 it can also mine without a node of yours, through a wallet server with the mining routes of Northbound. That way has run on mainnet against the public Northbound server, on an emulator and on one phone, for under half an hour in all; no block has been found through a server yet, so a found block being accepted that way has not been seen. An independent AI reviewer read the code of 0.1.2; its High and Medium findings are fixed, some of the Low ones are still open, and nobody independent has looked at the fixes or at the way through a server (see `review/`). From 0.3.0 it can also mine in a pool over stratum, the protocol of IronMiner. That way has been tried only on a computer, against the pool's own code with a made-up proof of work (`tools/stratum_check.py`), and not yet on a phone; nobody independent has read it yet. No independent human or security expert has reviewed the app. Use it on a phone you can afford to heat up.

## What it does, and what it does not

- It asks your node for a block template, puts the block together on the phone, tries nonces with RandomX (the library from the TrueNorth sources, unchanged) and hands a found block back to your node.
- It holds **no keys to coins**. It knows one address to mine to. The reward of a block it finds goes to that address, straight from the network.
- It needs somebody who hands out work: a pool, a wallet server with the mining routes of Northbound, or your own node. Through a wallet server the phone still puts the block together itself, so the server never holds a reward and cannot send it elsewhere. The server sees the phone's internet address and the address you mine to, and it could hand out no work or drop a block you found.
- In a pool it is the other way round: the pool hands out jobs and puts the blocks together, the phone sends shares (hashes that would have been a block on a much easier chain), and the pool pays for them by its own rules. A block found that way pays the pool, which then pays the miners, so a pool could also not pay. It sees the phone's internet address and the address you mine to. Over stratum that address travels unencrypted; it is public anyway.
- It is not on Google Play and will not be: Play does not allow mining on the device. The package is on the Releases page only.

## What to expect

One measurement, one phone (a 2026 flagship with 12 GB of memory), 16 November 2026, on the charger with the battery capped at 40 °C:

| Threads | Short run | Sustained |
|---|---|---|
| 2 | about 0.6 kH/s | about 0.42 kH/s at 38.5 °C |
| 4 | about 1.0 kH/s | about 0.40 kH/s at 38.6 °C |
| 8 | about 1.25 kH/s | about 0.64 kH/s at 40 to 42 °C, too warm to keep up |

A second, shorter run on 18 November 2026, two threads through the public server: 423 H/s in the first minute, about 210 H/s after five minutes with the battery at 37.7 °C and the system reporting that it was getting warm. The network was at about 320 kH/s that day; 0.2 to 0.4 kH/s then finds a block in one to two days on average.

Heat decides, not the number of cores. With the network at about 213 kH/s on that day, 0.4 kH/s finds a block about once a day on average. That falls as the network grows: at twenty times the hashrate it is a block in about two weeks. Older and cheaper phones will be much slower, and phones with less than about 6 GB of memory cannot use the fast mode at all.

Nothing here is a promise of income. NORTH is not traded anywhere I know of and may never be worth anything.

## Setting it up

### In a pool

Enter the pool's address in the form `stratum+tcp://host:port`, for example `stratum+tcp://pool.polarka.org:3333`, and the address you want to be paid to. Any pool for TrueNorth that speaks stratum the way IronMiner does will do; the app follows the pool's difficulty and its RandomX key. In the user field you may put a name for this phone (it goes after the address, as `address.name`); leave the password empty unless the pool asks for one. Then **Start mining**.

On a phone this is usually the better way: alone, a phone finds a block every day or two at best, and some go weeks without one; in a pool it is paid a little for every share, often. Polarka's pool pays per share (PPS) with a fee of 5 %, every ten minutes once 1 NORTH is owed. It is run by this project, so I am not a neutral party there either; pick the pool you trust.

### Without a node of your own

Enter the address of a wallet server that hands out work and leave the user and the password empty. The public server of the Northbound project is `https://api.northboundwallet.org`; it is run by nova9, not by this project, and it is a pre-release. Then the address for the reward, and **Start mining**. Nothing else is needed.

The app asks the server for a block template and waits there for the next one, asks once for the hash of the block that the RandomX key comes from, and hands a found block back. It sends no name and no password.

### With your own node: 1. The node

Give the miner a user of its own that may do three things and nothing else. In `truenorth.conf`:

```
rpcauth=phoneminer:<salt>$<hash>
rpcwhitelist=phoneminer:getblocktemplate,getblockhash,submitblock
rpcwhitelistdefault=0
rpcbind=<the node's address in your private network>
rpcallowip=<the phone's address in your private network>
```

- Make the `rpcauth` line with `share/rpcauth/rpcauth.py phoneminer` from the node's sources; it prints the line and the password.
- `rpcwhitelistdefault=0` keeps your other users (the cookie that `truenorth-cli` and `truenorth-miner` use) as they were.
- Keep `rpcbind=127.0.0.1` and `rpcallowip=127.0.0.1` as well if they are there.
- The node's RPC speaks plain http. **Name and password travel unencrypted**, so the phone and the node should meet inside a private network: a VPN such as Tailscale or WireGuard, or your home network. Do not open the RPC port to the internet.
- Restart the node. The mainnet RPC port is 9554.

### With your own node: 2. The app

Install the package from the Releases page (check it first, see below), then enter:

- **Node address**: `http://<node address>:9554`
- **RPC user and password**: the ones from step 1
- **Address for the reward**: your `north1z…` address
- **Threads**: 2 is the default and a good start. More threads mostly make more heat.
- **Battery temperature limit**: 40 °C by default. Mining pauses at the limit and goes on when the battery has cooled by two degrees.
- **Mine only while charging**: on by default.

Press **Start mining**. A permanent notification shows that the phone is mining and how fast; Android would stop the app otherwise. The log on the screen gets a line every minute with the rate, the battery temperature and the system's thermal status.

Before it mines, the app checks that this phone computes RandomX exactly as the network does, and refuses to mine if it does not.

## Heat and the battery

Mining keeps the processor busy for hours. The phone gets warm and its battery ages faster, more so while it charges. The defaults are careful on purpose. If you raise the limit or the threads, that is your decision and your battery. Take the phone out of its case, do not leave it under a pillow or in the sun, and stop if it becomes hot to the touch.

## Checking the package

- SHA-256 of the file is on the Releases page next to it.
- The package is signed with this certificate (SHA-256): `90:24:C9:7D:6F:78:6E:82:B0:CE:F3:A4:B2:C9:DD:8E:06:2D:18:72:47:EC:B8:A5:27:50:FC:C1:DA:3B:73:AC`. Check with `apksigner verify --print-certs`.
- The app asks for these permissions and no others: network, foreground service, notifications, wake lock.

## Building it yourself

You need the Android SDK with NDK 27.3.13750724 and CMake 3.22.1, and the TrueNorth sources at the tag `v30.5.4`. Point the build at RandomX inside them:

```
gradle :app:assembleRelease -PrandomxDir=/path/to/truenorth/src/randomx
```

Without a signing file the release build is unsigned. `tools/check-so.sh` checks the native library for a fault that breaks RandomX's arm64 code in a shared library (see the note in `app/src/main/cpp/CMakeLists.txt`). `tools/gbt_reference.py` is a second, independent way from a template to a block for regtest, used to check the app's own. `tools/northbound_mining_standin.py` is a stand-in for the mining routes of a Northbound server in front of a regtest node, written from that server's documentation, for trying the way through a server without the server. `tools/stratum_check.py` runs the app's stratum client (`Stratum.kt`, on a computer through `tools/stratum-jvm`) against the pool's own stratum code from polarka-agent: shares, a new tip, a new RandomX key, a new difficulty and a dropped connection.

## Who made this

I am Severka. I mine NORTH and hold some, and I run the Polarka pool, so I am not a neutral party. The app was written with an AI model under my direction; the review in `review/` was done by an independent AI reviewer that had not seen the code being written. Corrections are welcome in the issues.

## Licence

MIT for this app (see `LICENSE`). RandomX is under the BSD 3-Clause licence and TrueNorth Core under MIT; see `THIRD-PARTY.md`.
