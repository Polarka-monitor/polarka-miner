# Polarka Miner

An Android app that mines [TrueNorth](https://github.com/truenorth-project/truenorth) (NORTH) on a phone, solo, against a node you run yourself.

**Status: test build.** It has mined on regtest and against a node on mainnet on one phone. An independent AI reviewer read the code of 0.1.2; its High and Medium findings are fixed, some of the Low ones are still open, and nobody independent has looked at the fixes (see `review/`). No independent human or security expert has reviewed it. Use it on a phone you can afford to heat up.

## What it does, and what it does not

- It asks your node for a block template, puts the block together on the phone, tries nonces with RandomX (the library from the TrueNorth sources, unchanged) and hands a found block back to your node.
- It holds **no keys to coins**. It knows one address to mine to. The reward of a block it finds goes to that address, straight from the network.
- It needs a node. There is no pool in it and no public server yet. If you do not run a node, this build is not for you yet.
- It is not on Google Play and will not be: Play does not allow mining on the device. The package is on the Releases page only.

## What to expect

One measurement, one phone (a 2026 flagship with 12 GB of memory), 16 November 2026, on the charger with the battery capped at 40 °C:

| Threads | Short run | Sustained |
|---|---|---|
| 2 | about 0.6 kH/s | about 0.42 kH/s at 38.5 °C |
| 4 | about 1.0 kH/s | about 0.40 kH/s at 38.6 °C |
| 8 | about 1.25 kH/s | about 0.64 kH/s at 40 to 42 °C, too warm to keep up |

Heat decides, not the number of cores. With the network at about 213 kH/s on that day, 0.4 kH/s finds a block about once a day on average. That falls as the network grows: at twenty times the hashrate it is a block in about two weeks. Older and cheaper phones will be much slower, and phones with less than about 6 GB of memory cannot use the fast mode at all.

Nothing here is a promise of income. NORTH is not traded anywhere I know of and may never be worth anything.

## Setting it up

### 1. The node

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

### 2. The app

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

Without a signing file the release build is unsigned. `tools/check-so.sh` checks the native library for a fault that breaks RandomX's arm64 code in a shared library (see the note in `app/src/main/cpp/CMakeLists.txt`). `tools/gbt_reference.py` is a second, independent way from a template to a block for regtest, used to check the app's own.

## Who made this

I am Severka. I mine NORTH and hold some, so I am not a neutral party. The app was written with an AI model under my direction; the review in `review/` was done by an independent AI reviewer that had not seen the code being written. Corrections are welcome in the issues.

## Licence

MIT for this app (see `LICENSE`). RandomX is under the BSD 3-Clause licence and TrueNorth Core under MIT; see `THIRD-PARTY.md`.
