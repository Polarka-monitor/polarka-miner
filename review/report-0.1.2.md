# Polarka Miner 0.1.2: independent review

Date: 16 November 2026. Scope: every file named in the request (bench.cpp, CMakeLists.txt, Work.kt, NodeClient.kt, MinerService.kt, MinerActivity.kt, MainActivity.kt, Texts.kt, AndroidManifest.xml, the Gradle files, tools/gbt_reference.py, tools/check-so.sh), compared against the TrueNorth v30.5.4 sources (truenorth/miner.cpp, seed_key.*, randomx_wrapper.*, randomx/src, validation.cpp, rpc/mining.cpp, key_io.cpp, script/script.h, kernel/chainparams.cpp). Method: reading only. Nothing was built, installed or run, so every finding below comes from the code, and where that leaves doubt the finding says so.

## Verdict

**Publish after fixes.** The part that matters most for the network is sound: I found no difference between the block the app assembles and the block the reference miner assembles (coinbase, BIP34 push, witness commitment, both merkle roots, header, target, seed key, hash comparison). The app holds no coin keys and I found no path by which the RPC password reaches a log, an intent, the notification or a cloud backup. What stands in the way of handing a signed APK to strangers is the lifecycle around it: pressing Stop while the 2.3 GiB dataset is being built frees memory that threads are still writing (H1), a solved block is thrown away on a single network error at submit time (H2), the heat guard cannot fire on a phone that reports no battery temperature (M1), the fast mode is entered with no look at free memory (M2), and the repository as it stands contains personal helper scripts and no licence texts (M6, M7). None of the fixes is large.

Counts: Critical 0, High 2, Medium 7, Low 16, Note 14.

---

## High

### H1. Native lifecycle is not serialised: Stop during preparation frees the dataset under the threads that are filling it, and workers can start after the service is gone

Where: `MinerService.kt` 93-104 (onDestroy: `thread?.join(5000)` at 96, release thread at 97), 152 (`templates?.join(3000)`), 203-218 (prepare, then `Native.mineStart` at 216 with no look at `stop`), 219-226; `bench.cpp` 59-69 (`stop_workers`, `release_all` touch `g_workers`, `g_cache`, `g_dataset` with no lock), 189-229 (`prepare`), 319-320; `MainActivity.kt` 80-84, 109-111, 169-181.

What is wrong: `Native.prepare` runs on the template thread and takes tens of seconds to minutes on a phone. It cannot be interrupted (`NodeClient.interrupt()` only ends a long poll). onDestroy waits at most about 3.3 s (the control thread leaves its loop within 250 ms and then waits 3 s for the template thread) and then starts a thread that calls `Native.stop(); Native.release()`. `release_all()` frees `g_dataset` and `g_cache` while the `randomx_init_dataset` threads started at bench.cpp 219 are still writing into the dataset and reading the cache. That is a use after free of a 2 GiB block (a write to unmapped memory, so in practice a native crash). If the timing avoids the crash (for example Stop arrives while the template thread is still in `client.blockHash()` at 205, which is not interruptible either), `prepare` completes after the release, line 216 starts the workers and line 225 gives them a job: the phone then mines with no service, no notification, no wake lock, no heat guard and nobody taking the found nonces, while the screen says "not running". Only a new Start (whose `prepare` calls `release_all`) or the death of the process ends it.

The same hole exists in three more places: (a) `MainActivity.onDestroy` (82) releases while the benchmark thread may be inside `Native.prepare` (173) or `selfHash`; (b) `MainActivity.run`'s `finally { Native.stop() }` (110) and the onDestroy thread can both be inside `stop_workers()` at once and join the same `std::thread` twice (undefined; `join` on a thread that is no longer joinable throws, which in JNI code ends the process); (c) Stop followed quickly by Start, or Benchmark opened right after Stop, gives two threads in `prepare`/`release_all` at the same time, since `MinerState.running` is already false while the old template thread is still in native code.

Scenario: a user presses Start, sees "preparing memory for mining", notices a typo in the address and presses Stop five seconds later. The interface freezes for about three seconds (the join runs on the main thread), then the app crashes. The same happens at every key change (every 2048 blocks) if Stop falls into the rebuild.

Confidence: high for the logic; not reproduced (nothing was run). Pressing Stop 5 to 10 s after Start on a device, a few times, settles it.

Fix: (1) in bench.cpp one mutex around every lifecycle entry (`prepare`, `release`, `start`, `mineStart`, `stop`, `selfHash`, `hashHeader`), plus an atomic "cancel" that `stop`/`release` set before taking the mutex; build the dataset in slices (for example 64 slices per thread) and leave between slices when cancel is set. (2) In `newWork` check `stop` after `prepare` returns and do not call `mineStart`/`setJob` when it is set. (3) In onDestroy do not join on the main thread: start one teardown thread that joins the control and template threads without a time limit and only then calls `Native.stop(); Native.release()`. (4) Route every lifecycle call of both activities and the service through one single-thread executor (or a process-wide lock) so that a new Start or a benchmark waits for the previous teardown.

### H2. A solved block is discarded when `submitblock` fails in transport; there is no second attempt

Where: `MinerService.kt` 268-284 (the `catch` at 280-283 counts the block as "refused", logs "could not be sent" and moves on), `NodeClient.kt` 70-73.

What is wrong: any exception from `client.submit` (connection refused, connect timeout after 10 s, read timeout after 60 s, a Wi-Fi roam, Tailscale re-keying, a truncated answer) ends the life of that block. `submitted` has already been raised (260), the job has been cleared (261), and line 284 asks for fresh work. The nonce and the block are gone although the block was valid and the tip may not have moved.

Scenario: on mainnet a phone finds a block perhaps once in a long while; that is the whole point of running the app. The phone talks to the node through a VPN over Wi-Fi or mobile data, where a failed request now and then is ordinary. One such failure at the wrong second and the reward is lost, with a log line that reads like a refusal by the node.

Fix: keep the solved block and retry (for example up to five times over a minute, with a short pause) for as long as the newest template still names the same previous block; treat the node's answer "duplicate" as success (the first attempt arrived and only its answer was lost). Do the submitting on its own thread: today the control thread is blocked for up to about 70 s per attempt, and for that time the heat and charging guard at 288-312 is not evaluated (the workers keep hashing if the find was not on the current job). Count and word "could not be sent" apart from "refused".

---

## Medium

### M1. The battery-temperature guard fails open when the phone gives no temperature

Where: `MinerService.kt` 118-123 (default 0 for `EXTRA_TEMPERATURE`, also when the sticky intent is null), 296-297.

What is wrong: a missing reading becomes 0.0 °C, and `t >= limit` is then never true. The only remaining protection is `currentThermalStatus >= SEVERE`, and that call returns 0 (NONE) on devices whose thermal service reports nothing. On such a device (tablets and boxes without a battery thermistor, some custom ROMs, emulators, which report a constant) the guard never fires and the screen shows "battery 0.0 °C" as if all were well. Note that `MainActivity.state()` (141) uses -1 as the default, so the two screens already disagree about what "missing" looks like.

Fix: treat a null intent, a missing extra or an implausible value (at or below 0 °C, above about 80 °C) as "temperature unknown" and do not mine in that state unless a second source is alive; say so on the screen. As a second source use `PowerManager.getThermalHeadroom()` (API 30, the app's minimum is 31): NaN means "no data", values near 1.0 mean the severe threshold is close. Consider pausing at MODERATE rather than only at SEVERE.

### M2. The fast mode is entered without looking at free memory; the fallback to the light mode will rarely trigger

Where: `MinerService.kt` 207-213; `bench.cpp` 206-211. The reference asks for 2560 MiB available before choosing fast (`randomx_wrapper.h` 26, 32).

What is wrong: an allocation of 2 GiB on Android normally succeeds whatever the free memory is; the pages are only taken when the dataset is written. On a phone with 4 to 6 GB the system then compresses and kills: first every other background app, in the end possibly this process. Because the service is START_NOT_STICKY, mining then ends without a word. The dataset is incompressible, so zram does not help and the phone becomes sluggish for the duration. "error: the dataset could not be allocated" (209) is therefore not the failure that will be seen in practice.

Confidence: medium (depends on the device; not run). Starting the app on a 4 GB and a 6 GB phone and watching the low-memory killer's log settles it.

Fix: before the fast mode read `ActivityManager.MemoryInfo`; require `availMem` of about 2.6 GiB and `lowMemory == false`, otherwise go to the light mode straight away (or refuse and say why). State the memory need on the start screen, not only in the log.

### M3. The native library is probably not aligned for 16 KB pages

Where: `app/build.gradle.kts` 16 (NDK 27.3), 26 (cmake arguments), 21 (targetSdk 36); `CMakeLists.txt` (no `max-page-size`).

What is wrong: NDK r27 links with 4 KB segment alignment unless `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` is passed (r28 changed the default). On a device that runs with 16 KB pages the loader refuses such a library on Android 15, which here means an `UnsatisfiedLinkError` at the first use of `Native` and a crash; Android 16 may load it in a compatibility mode with a warning dialog. Newer flagship phones, the ones most likely to be used for this, are where 16 KB pages appear first.

Confidence: medium; I did not inspect the built library. `llvm-readelf -l libpolarkaminer.so` settles it: the LOAD segments must show `Align 0x4000`.

Fix: add `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` to the cmake arguments (or move to NDK r28 or later) and add the alignment to what tools/check-so.sh verifies. bench.cpp 44-47 uses a fixed 4096 for the probe page; it works on 16 KB pages but `sysconf(_SC_PAGESIZE)` is cleaner.

### M4. Name and password travel in clear text over http, and nothing on the screen says so

Where: `AndroidManifest.xml` 17 (`usesCleartextTraffic="true"`), `NodeClient.kt` 21, 32 (Basic authorisation on every request), `MinerService.kt` 142 (any http address is taken), `MinerActivity.kt` 63-65 (labels).

What is wrong: the warning exists only as a code comment (NodeClient.kt 16-18). The screen gives a private-looking example address and nothing more. A stranger whose node sits on a rented server will type its public address; the RPC password is then readable on every network in between. If that RPC user is not the limited one the label recommends and the node has a wallet, this is a path to the coins on the node, which sits badly with "there are no keys to coins in this app".

Fix: when the address is http and the host is not loopback, 10/8, 172.16/12, 192.168/16, 100.64/10 or link-local, show a plain warning and ask for a confirmation before starting. Put one sentence on the screen: the password is sent unprotected, use a private network and a user limited with rpcwhitelist. Optionally refuse to start when the node answers a call that the limited user should not be allowed.

### M5. The mining path never checks that this phone computes the right hash, and worker start failures are invisible

Where: `MinerService.kt` 208-216 (prepare, then mineStart, no check); compare `MainActivity.kt` 182-193 (the benchmark does check against a reference value). `bench.cpp` 137 (`g_failed++`) is read only by the benchmark (`MainActivity.kt` 163).

What is wrong: the CMake comment (21-25) records that a wrong link once made the generated code jump out of its buffer. A phone on which the generated code, the dataset or the AES path gives a different hash than the network would mine for days and never find a block, or find "blocks" the node refuses as high-hash. Nothing in the mining path would notice. Likewise, if `randomx_create_vm` fails in the workers, they exit, `Native.failed()` is never asked, and the screen shows "mining block N: 0 H/s" for ever.

Fix: after `prepare` and before `mineStart`, hash one fixed 80-byte input twice with the real key: once with a VM made the way the workers make it, once with a plain interpreter VM without the dataset (flags: V2 only). Refuse to mine if they differ and say so. After `mineStart` wait a moment and read `Native.failed()`; report and stop if it is not zero. Cheap extra: check each found nonce with the interpreter VM before sending.

### M6. tools/ holds personal and one-off scripts that should not be published

Where: `tools/node-phone-user.sh` (line 2 names a person and an assistant; the script edits a private node's configuration, handles a password and refers to private folders under the home directory and to a file-drop to a phone), `tools/regtest-node.sh` 3-8, `tools/regtest-rpc-user.py`, `tools/emu-mine.sh`, `tools/emu-checks.sh` (fixed emulator serial, private paths), `tools/patch_011.py`, `tools/patch_012_en.py`, `tools/patch_stage1_a.py` (scripts that once rewrote the sources; of no use to a reader and they show how the code was produced), `tools/build-rxflow.sh`, `tools/build-rxtests.sh`, `tools/check-so.sh` 6-7 (all source `$HOME/north-build/env.sh`).

I deliberately did not read node-phone-user.sh beyond its header, since it deals with a password; a pattern count shows two lines in it that match a password or address pattern, and one in regtest-rpc-user.py. They must be read by the owner before anything is published.

Fix: publish only what a stranger can use: gbt_reference.py, the vectors (after a look that they hold regtest data only), check-so.sh made portable (NDK path from `ANDROID_NDK_HOME`, host tag detected, no env.sh). Keep the rest out of the public repository.

### M7. No licence, no notices, no README

Where: project root (no LICENSE, NOTICE, README, no .git yet); `bench.cpp` 2, `Work.kt` 49-52.

What is wrong: the APK contains RandomX in binary form. Its BSD 3-clause licence requires the copyright notice, the conditions and the disclaimer to be reproduced in the documentation or other materials that come with a binary; RandomX in turn carries Argon2 and BLAKE2 code with their own notices. Work.kt and gbt_reference.py are written from the MIT-licensed reference miner and should carry the TrueNorth copyright line and the MIT text. Without a licence file of its own the app is not open source at all, whatever the repository's visibility.

Fix: add LICENSE (the app's own choice), a NOTICE or THIRD-PARTY file with the RandomX, Argon2, BLAKE2 and TrueNorth texts, show the same texts in the app (a "Licences" button is enough) or ship them next to the APK, and add a README that says what the app does, what it needs (arm64, Android 12, about 2.6 GiB free memory, an own node), how to build it and how to verify the release signature.

---

## Low

**L1. Wake lock, long poll and a 4 Hz loop go on while paused.** `MinerService.kt` 86, 252, 298-311. With "only while charging" (the default) a phone that is unplugged and forgotten keeps a partial wake lock, a network connection that is renewed with every block, a battery read every second and a control loop every 250 ms, for as long as the service lives. The texts do not mention it. Fix: when paused for "not charging" for more than a few minutes, release the wake lock and stop polling; come back on `ACTION_POWER_CONNECTED`, or say plainly on the screen that the app stays awake while paused.

**L2. Settings changed while mining are silently ignored; out-of-range values are silently clamped.** `MinerActivity.kt` 90-104 (values are stored only on Start), `MinerService.kt` 138-139. A user who lowers the temperature limit or the thread count because the phone is hot changes nothing until Stop and Start, and nothing tells him. A typed limit of 60 stays on the screen while the service uses 45. Fix: disable the fields while running (or apply limit, threads and the charging choice live) and write the clamped values back to the fields.

**L3. The key is tracked by height, not by hash, and is not invalidated before a rebuild.** `MinerService.kt` 203-217. A reorganisation that replaces the seed block (64 or more blocks deep) leaves the app hashing with the old key until restart: every hash is wasted. Separately, if both `prepare` calls fail (215), `keyHeight` still holds the old value although the native side was released; a later template with that old seed height would skip `prepare` and hand a job to workers that do not exist. Fix: remember the seed hash, re-read it with each new previous block near an epoch start (or every N templates), and set `keyHeight = -1` before calling `prepare`.

**L4. Resuming after a pause hashes the same nonces of the same header again.** `MinerService.kt` 308, `bench.cpp` 143-148. The job number returns from 0 to the same id, so each worker starts again at its first nonce. A found nonce would be found twice (the second is filtered at 259), the rest is repeated work. Fix: on resume ask for fresh work (`refresh = true; client.interrupt()`), which also renews the time field.

**L5. Finds can be dropped without a word in edge cases.** (a) Stop: the control loop leaves (251) without a last `takeFound`; a nonce found in the last 250 ms is lost. (b) `Native.mineStart` clears the list (bench.cpp 284). (c) A job that has left the map of six (222) gives `jobs[id] == null` and a silent `continue` (262-264) after `submitted` was already raised. (d) The high-water mark drops a find for an older job on the same tip even when the newer job's block later fails to be sent. None is likely on mainnet; each deserves at least a log line, and (a) a final pass.

**L6. The RPC password is stored as plain text and may travel to a new phone.** `MinerActivity.kt` 95-98, `AndroidManifest.xml` 14. `allowBackup="false"` stops cloud backup, but for apps that target Android 12 or later it does not stop device-to-device transfer. Fix: add `android:dataExtractionRules` that exclude the preferences for both cloud backup and device transfer; optionally keep the password under an Android Keystore key.

**L7. The benchmark screen is Czech only, has no heat cut-off and carries stale statements.** `MainActivity.kt` 63-71, 77, 99, 104-108, 130-145, 165-193, 205-229: every text is Czech although the app's default language is English. Line 189 asks the reader to "send me this output", with no "me" for a stranger. The class comment (41-45) says the app has no network permission, which stopped being true. The 15-minute run (216-234) loads every core with the screen forced on and the phone on the charger, and stops for nothing: no battery limit, no thermal status. Fix: `tr()` pairs as elsewhere, the same guard as the service (at least stop at the service's default limit or at SEVERE), reworded request, corrected comment.

**L8. NodeClient: no limit on the size of an answer, redirects followed, system proxy used.** `NodeClient.kt` 26-41. `readBytes()` takes whatever comes; a faulty or hostile node (or anything answering on that port) can exhaust the heap, and an `OutOfMemoryError` is not caught by `catch (e: Exception)` in the template loop. Fix: cap the answer (for example 32 MiB), set `instanceFollowRedirects = false`, open with `Proxy.NO_PROXY`.

**L9. Heat guard details.** `MinerService.kt` 296-297: the two-degree hysteresis applies to the battery temperature only; when the pause came from the thermal status, mining resumes 30 s after the status drops from SEVERE to MODERATE and may swing between the two. Require LIGHT or lower to resume. The guard runs on the thread that also submits blocks (see H2). The upper limit of 45 °C is selectable while the phone charges; that is the edge of what cell makers allow for charging, which the start text's general warning covers only loosely.

**L10. The dataset build ignores the pause, the heat and the thread setting.** `MinerService.kt` 208: `prepare` uses every core for the whole build, also when the phone is paused for heat or not charging, and also on battery right after Start with "only while charging" set. Fix: do not prepare while paused; use the user's thread count (or half the cores) for the build.

**L11. Template rules are ignored; a signet address is accepted.** `Work.kt` 59, 184-208. The node marks rules a miner must understand with "!" (`!signet`, rpc/mining.cpp 953). The app would mine signet blocks without the block signature and the node would refuse each one. Fix: refuse a template whose `rules` hold a "!" entry other than `!segwit`, and drop `snorth` from the accepted prefixes until signet is supported.

**L12. Status texts that mislead.** (a) "cannot start: reason" (155) is overwritten by "not running" in onDestroy (100) a moment later; the reason survives only in the log. (b) During a key change the status reads "waiting for work from the node" (329, since `keyHeight >= 0`) while the phone is in fact rebuilding memory. (c) The notification title says "is mining NORTH" (110) also while paused or waiting. (d) "refused" counts blocks that never reached the node (281). (e) `keyHeight` is read on the control thread and written on the template thread without `@Volatile`.

**L13. Choice of cores and pinning fail silently.** `MinerService.kt` 47-53: if `cpuinfo_max_freq` cannot be read, every core scores 0 and the first N cores are taken, which on most phones are the slow ones. `bench.cpp` 87-93: the result of `sched_setaffinity` is ignored, and the system may move a background app's threads to a restricted set of cores anyway. Effect: a lower rate than the benchmark promised, with no hint why. Fix: log the chosen cores and a failed pin; fall back to no pinning when frequencies are unknown.

**L14. Tapping the notification stacks a new screen each time.** `MinerService.kt` 107: the intent has no flags and the activity has the standard launch mode, so every tap puts another `MinerActivity` on the task, each with its own copy of the fields. Fix: `launchMode="singleTop"` and `FLAG_ACTIVITY_SINGLE_TOP`, or use the launcher intent.

**L15. The build is not self-contained or repeatable by others.** No Gradle wrapper and no dependency verification in the tree; RandomX is taken from a folder under the builder's home (`app/build.gradle.kts` 10-11) with no pinned revision or checksum; a release build without the signing file silently yields an unsigned APK (31-45), and `wantsRelease` only looks for "Release" in the task name; `tools/check-so.sh` is tied to one machine (6-7), is not part of the build although CMakeLists.txt 25 says it "fails the build check", and is not executable. Fix: commit the wrapper, document `-PrandomxDir` with the expected commit or a SHA-256 of the folder, fail the release build when signing is missing, call check-so.sh from a Gradle task after the native build.

**L16. Polling without bounds in two cases.** `MinerService.kt` 177-196: a template without `longpollid` makes the loop ask again at once, without pause (not possible with the project's own node, possible with a proxy in between). A wrong password is retried every five seconds for ever, which fills the node's log with failed logins. Fix: a minimum interval of a few seconds between plain requests; after a 401 stop and ask the user.

---

## Notes

- **N1.** The address's network is not compared with the node's. No coins are lost (the output script is the same for every prefix), but the log can say "testnet" while mainnet is mined. `getblockhash 0` is among the allowed calls and identifies the network.
- **N2.** `Work.kt` 196 trusts the template's `txid` values and does not check their length; the wtxids are covered by the commitment check at 192, the txids by nothing. Only a faulty node could exploit it, and the cost is a refused block.
- **N3.** Logcat and the notification can show the first ten and last six characters of the address (148), the node's messages and, in connection errors, the node's host and port (173, 192, 335). No password. Acceptable; worth a line in the README.
- **N4.** No `FLAG_SECURE`: the recent-apps picture shows node address, user name and reward address (the password is masked).
- **N5.** If the user refuses the notification permission the service still runs, but the promised permanent notification is not shown in the drawer.
- **N6.** START_NOT_STICKY: after the process is killed (memory, vendor battery managers, "stop" in the system's list of running apps) mining ends silently. No exemption from battery optimisation is requested; with "also when not charging" Doze may cut the network. Not checked on devices.
- **N7.** `NodeClient.kt` 39: HTTP 403 is also what the node answers when the phone's address is not in `rpcallowip`; the message names only the whitelist case.
- **N8.** `hashHeader` and `meetsTarget` (bench.cpp 247-265) are not used by the app; the `log` library is linked (CMakeLists.txt 19) and not used.
- **N9.** No launcher icon, no `res` folder, no unit tests in the tree; tools/vectors is not consumed by any test that ships with the sources. A JVM test that feeds the three vectors to `Blocks.assemble` would pin the consensus-relevant code.
- **N10.** With targetSdk 36 the system draws the app edge to edge and ignores the portrait lock on large screens; the layout uses a fixed top padding (`MinerActivity.kt` 56). Whether the Start button or the log end up under system bars or the keyboard needs a look on a device. Fields have no ids, so text typed but not yet started is lost when the activity is recreated.
- **N11.** Python helper against Kotlin: same blocks for the same input as far as I can read them. Differences without consequence: the helper accepts any prefix and no upper-case address (25-50), does not range-check the target (133-134), cannot encode a negative version (129). It refuses anything but regtest (168-170), as it should.
- **N12.** `Work.kt` 179: the `else` branch cannot be reached, since the exponent is limited to 32 at 174. Exponents below 3 and of 33 or 34, which the node's decoder allows in theory, are refused; no network uses them.
- **N13.** `check-so.sh` looks for the text `randomx_`. That covers the C interface and the hand-written arm64 labels (the ones the copied code branches to, for example jit_compiler_a64_static.S 374), not the C++ names in the `randomx` namespace. With `--exclude-libs,ALL` those are hidden as well; the script simply does not prove it.
- **N14.** After the node becomes unreachable the last template is mined for up to 90 s (171); after the node loses all its peers, for up to about four minutes (long-poll timeout of 150 s plus the 90 s). A block found in that time is likely stale. A design choice; worth stating.

---

## Checked and found correct

- **BIP34 height push** (`Work.kt` 83-93) against `CScript::push_int64` (script.h 441-456) and the check in validation.cpp 4403-4406: height 0 gives OP_0; 1 to 16 give OP_1 to OP_16; 17 gives `01 11`; 128 gives `02 80 00`; 32768 gives `03 00 80 00`; the sign-bit rule adds a zero byte exactly when the top byte has bit 7 set. The script is always 10 bytes or more, inside the 2 to 100 limit.
- **Coinbase** (150-168) against `BuildCoinbase`/`AssembleBlock` (miner.cpp 412-498): version 1, null previous output, sequence 0xffffffff, extranonce as an 8-byte little-endian push with a random start, reward output first, commitment output second, one 32-byte zero witness item, lock time 0; serialisation with marker and flag only when there is a witness; txid computed from the form without witness.
- **Witness commitment** (147-148, 189-193): `6a24aa21a9ed` plus SHA256d(witness root, 32 zero bytes), coinbase wtxid as zero, checked against the node's `default_witness_commitment`.
- **Merkle roots** (95-103): last entry doubled on odd counts, single entry returned as is.
- **Header** (198-200): version, previous hash, root, time, bits in the node's byte order, 76 bytes plus a little-endian nonce (bench.cpp 154-158), the same 80 bytes the node hashes (primitives/block.cpp 22-26).
- **Target and comparison**: compact decoding for exponents 3 to 32 incl. regtest's `207fffff`, sign bit and zero mantissa refused, cross-check against the template's `target`; `meets_target` (bench.cpp 79-85) equals `UintToArith256(h) <= target`.
- **Seed key**: `seedHeight` equals `SeedHeightForNextHeight`; zero key below height 2112; key bytes in internal order (`Hex.reversed`), as `randomx_init_cache(seed_key.begin(), 32)` takes them.
- **RandomX flags**: recommended flags plus V2, FULL_MEM only for VMs with a dataset, one VM per thread, as in randomx_wrapper.cpp 47-59. The claim that RandomX ignores a refused `mprotect` is true (virtual_memory.c 159-199), so the RWX probe and the SECURE flag are justified.
- **Nonce split** between workers equals the reference (index, index + count, ...), and exhaustion is handled (bench.cpp 165-166).
- **Address decoding**: bech32m constant, checksum, padding rule, version 2 with 32 bytes only (`WitnessV2QRH`, key_io.cpp 192), prefixes equal to chainparams (north, tnorth, tnorth4, snorth, rnorth); mixed case refused.
- **Link flags**: `--exclude-libs,ALL` with `-Bsymbolic` does what the comment says for the branch to a global label in the hand-written arm64 code.
- **Job hand-over**: `g_job` under its mutex, the found list under its mutex, `set_job`/`takeFound` safe against running workers; no lock-order problem between the Kotlin lock and the native mutexes.
- **Service declaration**: special-use type, permission and subtype property present; `startForeground` is called in `onStartCommand` before anything else; the pending intent is immutable; service and benchmark activity are not exported; the exported launcher activity reads nothing from its intent; the wake lock is released in onDestroy and a second start is ignored.
- **Password**: never logged, never in an intent or the notification, masked on the screen, cloud backup off.
- **Screen claims**: "no keys to coins" is true; defaults (4 threads, 40 °C, only while charging) agree between screen and service; the English and Czech halves of every `tr()` pair in MinerService, MinerActivity, NodeClient and Work say the same thing.
- **Node side**: on mainnet `getblocktemplate` refuses during initial download and without peers (rpc/mining.cpp 766-774), so the app cannot mine on an unsynchronised node; the errors reach the screen through `trouble()`.
- **Long-poll handling**: a timed-out long poll leads to a plain request, so the time field is at most about 150 s old; a new tip replaces the job at once; the same-previous-block filter and the high-water mark prevent competing submissions.
- **Signing material** lives outside the project and `.gitignore` excludes keystores and `signing.properties`. JNI names match the Kotlin declarations and minification is off.

## Could not be checked

- Anything that needs the app to run: the crash in H1, memory behaviour in M2, the foreground service on Android 12 to 16, whether `HttpURLConnection.disconnect()` from another thread really ends a waiting long poll on current Android (and whether it does so on the main thread for https), notification behaviour, layout under edge-to-edge.
- The built library and the APK: relocations and exports (what check-so.sh tests), page alignment (M3), whether hardware AES is in use, the signature and its scheme.
- The reference value of the self-check hash (`MainActivity.kt` 187).
- The three vectors in tools/vectors (not opened, since printing them was outside the limits) and whether the Kotlin code reproduces them.
- tools/node-phone-user.sh beyond its first lines, and the other helper scripts beyond their header comments and path references.
- The versions of the build plugins (Kotlin 2.4.21, Android plugin 8.13.2) against what is published.
- Signing files, test credentials and everything under the excluded folders, as instructed.
