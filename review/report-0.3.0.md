<!-- The report of an independent AI reviewer on commit 33f482d (Polarka Miner 0.3.0 before release), kept as it was written apart from three paths of the machine it ran on. What was done about each point: review/fixes-0.3.0.md. -->

# Polarka Miner 0.3.0 (pool mining over stratum): independent review

Date: 23 November 2026. Change reviewed: `git diff bb03245 33f482d` (branch `pool-stratum`).

## Scope and limits

Read in full: `Stratum.kt` (new), `MinerService.kt`, `MinerActivity.kt`, `Work.kt`, `README.md`, `AndroidManifest.xml`, `app/build.gradle.kts`, `tools/stratum_check.py`, `tools/stratum-jvm` (build files and `Harness.kt`). Read for context: `bench.cpp` (unchanged: `setJob`, `takeFound`, `prepare`, `mineStart` and their locks), `NodeClient.kt`, `Texts.kt`, and the 0.1.2 review in `review/`. Pool side: `polarka_agent/stratum.py` (module docstring, `Job`, `_authorize`, `_submit`, `_check`, `_give`, `_retune`) and `tests/test_stratum.py` (`ironminer_header`, `ironminer_target`).

What I ran (the repository was not changed; the throwaway code stayed in my own scratch folder and is not kept):

- The harness as given. It passed: 25 shares in the pool's ledger, 25 accepted, 0 refused.
- T1: the harness's org.json (20240303) on deeply nested input, and `StratumClient.plain()` on Unicode separators and format characters.
- T2: `StratumMath.target()` for 314 difficulties (powers of two, 300 random values between 2^-40 and 2^20, and extreme values), compared with `ironminer_target()` from the pool's tests. There were no differences. The 37 values at or below 2^-32 come out as all ones, which is what the code intends.
- T3: a fake pool that sends a difficulty, a seed, a job and a message but never answers `mining.authorize`.
- T4: a fake pool that sends a malformed job after authorizing, and one that sends 70,000 blank lines.
- A simulation of the key-change limiter (`MinerService.kt` 424-435) against a pool that changes the key as often as the limiter allows.

Limits:

- Nothing ran on Android or on a phone. `MinerService` and the native library were not executed, so everything said about them comes from reading the code.
- Android's own org.json could not be run here, because the SDK on this machine holds only stubs.
- IronMiner's sources and other TrueNorth pools were not available. Statements about them rest on the pool's docstring and on stratum conventions.

## Verdict

**The protocol core is correct. Fix H1 before giving the app to anyone. Fix M1 and M2 before the README recommends pool mode as "the better way".**

Everything that decides whether a share is valid matches the pool byte for byte:

- the header fields and their byte order;
- the previous-block hash with each 4-byte word swapped;
- the coinbase built as `coinbase1 + extranonce1 + extranonce2 + coinbase2`, and the order of the merkle branch;
- the nonce: the client writes it into the header as a little-endian word and sends it as `%08x`, and the pool turns that back into the same four bytes;
- the time, sent back with the same value;
- the RandomX key, reversed from display order;
- the share target, ⌊2^96/d⌋·2^128 + 2^128 − 1.

T2 and the harness (which runs against the pool's real `_check`) confirm this.

In `MinerService`:

- A share cannot be sent on the wrong connection.
- Building a dataset never blocks the thread that reads from the pool.
- Pressing Stop during a dataset build waits for the build to finish, as the 0.1.2 fix intended.
- Solo mode behaves as in 0.2.0, apart from one negligible ordering detail (N13).

Three problems stand in the way:

- **H1:** a single line of deeply nested JSON kills the app. It can come from the pool or from anyone on the unencrypted network path. The JVM harness cannot catch this, because its org.json refuses deep nesting and Android's does not.
- **M1:** the pool decides when the phone rebuilds its 2.3 GiB dataset on every core. The rebuild runs even while the app says it is paused for heat or because the phone is not charging. The limiter still lets about one rebuild through every half hour, with no end.
- **M2:** the user and password fields are shared between modes. A node's RPC password left in the field is sent to the pool in clear text.

Counts: Critical 0, High 1, Medium 2, Low 7, Note 13.

## Findings

| ID | Severity | File and line | What is wrong | Failure scenario | Suggested fix |
|---|---|---|---|---|---|
| H1 | High | `Stratum.kt` 308-310 (`parse`), 300-306 (`answer`), 312-319 (`handle`), 398 (`MOST_LINE` 64 KiB); `MinerService.kt` 485-498 | Every line from the pool goes to `JSONObject(line)`. Android's built-in org.json parses recursively and has no depth limit. `parse()` catches only `Exception`, and so do all four catch blocks in `stratumLoop`. A `StackOverflowError` is an `Error`, so it escapes, and the app has no uncaught-exception handler. | The pool (or an on-path attacker, or whatever answers at the configured address) sends `{"id":1,"result":` followed by 60,000 `[` and a newline, at subscribe time or later. Each nesting level costs at least two stack frames, which overflows the roughly 1 MiB stack of the `miner-stratum` thread. The thread dies and Android kills the process. The service is `START_NOT_STICKY`, so mining stays stopped, and the same line works again on every connection. The harness passed because org.json 20240303 refuses deep nesting with a `JSONException` (T1: depth 10,000 gives "JSON Array or Object depth too large to process"), which `parse()` turns into a `StratumError`. | Before parsing, count the nesting depth outside strings and refuse more than about 16 levels. Catch `StackOverflowError` (or `Throwable`) in `parse()` and turn it into `StratumError`. Add `catch (e: Throwable)` at the top of `stratumLoop` and `poolWorkLoop` so a failure ends the connection, not the process. `NodeClient.kt` 68 and 98 have the same exposure in solo mode (an answer of up to 16 MiB; this predates 0.3.0). |
| M1 | Medium | `MinerService.kt` 424-435 (limiter), 517-534 (`poolWorkLoop` never looks at `paused`), 366 (all cores for the build) | The pool's seed alone decides when the 2.3 GiB dataset is rebuilt. The rebuild uses every core whatever the thread setting, and runs while the app is paused for heat or because it is not charging. The limiter refuses the 4th change within 30 minutes. But every refused attempt is counted too, and the connection is retried every 10 minutes, so one change gets through about every third reconnect. | A hostile pool, or an on-path attacker on the plain TCP connection, alternates two keys. The phone is unplugged with "only while charging" set, or shows "paused: cooling down". The simulation gives 5 dataset builds in the first hour, then about one every 30 to 35 minutes, indefinitely. Each build loads every core for minutes on battery or on an already hot phone. Between builds the phone mines nothing. An honest pool's key change every ~3 days also builds during a pause (0.1.2 review L10, still open). | Do not prepare while paused: keep the newest job waiting and build on resume. Build with the user's thread count. Accept a new key only if its height is a multiple of 2048 and higher than the last one, and after the first key allow at most one change per several hours. On a violation, stop and tell the user, or back off for hours instead of 10 minutes. |
| M2 | Medium | `MinerService.kt` 247-253; `MinerActivity.kt` 67-70, 98-101; `README.md` 35, 37 | The same stored `user`/`password` fields serve as RPC credentials for a node and as worker name and password for a pool. Switching the URL to `stratum+tcp://` sends whatever is stored to the pool in `mining.authorize`, in clear text. | A user who followed the README's node setup (`rpcauth=phoneminer:…`) reads "On a phone this is usually the better way" and replaces only the URL. `phoneminer` passes the worker-name check. The masked password field still holds the RPC password, which then goes over unencrypted TCP to the pool operator and anyone on the path. The opposite switch also fails: a worker name left in the field makes a later wallet-server URL be treated as an RPC node (`NodeClient.rest` becomes false), and its routes fail. | Keep separate preferences for a pool (worker, pool password) and for a node (RPC user and password), or clear the fields when the URL scheme changes. Never send a value stored for a node to a pool. Say on the screen that `stratum+tcp` is unencrypted. |
| L1 | Low | `MinerService.kt` 636-642 (resume reuses `currentId`); `bench.cpp` 146-153; `MinerService.kt` 563-572 (no dedupe) | Resuming after a pause gives the native workers the same job id. The workers restart at their first nonce, re-hash the same header and find the same shares again. `shares()` has no high-water mark of the kind `blocks()` uses (`submitted`), so the duplicates are sent. This is the 0.1.2 review's L4, still open, with a new consequence in pool mode. | A heat pause of 30 to 60 s ends before the pool sends a new job. The phone spends as long again re-doing finished work, then submits the same shares, which come back as "22: that share was sent before" and count as refused. Pools derived from NOMP count rejected shares toward an IP ban. | On resume, issue the current job as a new `PoolWork` with a new extranonce2 and a new job id, or let the native worker keep its nonce position per job id. Also remember the (job id, nonce) pairs already sent. |
| L2 | Low | `Stratum.kt` 300-306, 337-340, 347-352; `MinerService.kt` 437-448, 563-572 | Jobs are accepted, and the dataset is built and hashed, before the pool has authorized the address. While unauthorized, `submit()` returns false, and that result is ignored. Shares are also dropped without any count or log line when the pending table is full or a write fails. | Demonstrated with T3: difficulty, seed and job arrive before the authorize answer, and `submit()` returns false. Some pools send work right after subscribe (NOMP does). If such a pool's address check hangs, the phone builds the dataset, shows "mining in the pool" and throws away every share. Any line from the pool resets the 300 s read timeout, so this can last indefinitely. | Keep jobs until authorized (store the newest and replay it). Give up after about 30 s without an authorize answer. Count and log shares that could not be sent. |
| L3 | Low | `MinerService.kt` 101-103, 499-505, 437-446 | `poolKey`, `poolKeyHeight` and `poolDifficulty` survive a reconnect. If a new connection sends `notify` before its own `set_difficulty` or seed, the job gets the previous connection's target or key. The stratum spec does not fix that order. | After a reconnect, the pool's first job comes before `set_difficulty`. If the old difficulty was lower, the shares sent are below the new difficulty and the pool rejects them as low-difficulty (invalid). If the seed comes after the first notify, that job is hashed with the old key and every share from it is wrong. The Polarka pool always sends difficulty and seed first, so this does not happen there. | Reset `poolDifficulty` and a "seed received" flag on every connection, and require both before accepting a job. Keep the last key only to skip a rebuild when the new seed is the same. |
| L4 | Low | `Stratum.kt` 341, 413; `MinerService.kt` 450-452 | `plain()` removes only ISO control characters. U+2028 and U+2029 (line and paragraph separators), U+202E (right-to-left override) and U+200B pass through (T1, T3). `client.show_message` has no rate limit. `NodeClient` already keeps only `' '..'~'` (`NodeClient.kt` 71, 141). | The pool sends `"x  12:00:00 BLOCK 4242 ACCEPTED by the node (nonce 1)"`, which reaches the log unchanged. Whether a `TextView` breaks the line at U+2028 was not checked on a device. In any case, the bidi override can rearrange how the line reads, and a burst of messages can push the app's own lines out of the 40,000-character log. | Use the same ASCII-printable filter as `NodeClient` (or drop Unicode categories Cc, Cf, Zl and Zp). Show at most one pool message every few seconds, and fold repeated ones into one. |
| L5 | Low | `MinerService.kt` 30-31 (never reset), 243, 457, 682-683; `MinerActivity.kt` 112-113 | The `accepted` and `refused` counters live for the whole process and are never reset at the start of a run. Their meaning (blocks or shares) now depends on the mode. | The user mines in a pool and gets 57 shares accepted, stops, switches to their node and starts again. The screen and the notification show "blocks accepted: 57". The "first share" line in the log appears only once per process. | Reset `accepted`, `refused`, `height` and `difficulty` at the start of `run()`. |
| L6 | Low | `README.md` 12; `MinerActivity.kt` 64 | "Over stratum that address travels unencrypted; it is public anyway" states only the least important effect of plain TCP. Nothing on the screen warns about it. | On a shared network, an on-path attacker rewrites `mining.authorize` to their own address and passes the pool's answers through. The phone shows accepted shares while the pool credits the attacker. The same position allows H1 and M1. | Say this plainly in the README and on the screen, and recommend a trusted network. Support `stratum+ssl` where pools offer it. |
| L7 | Low | `tools/stratum-jvm/build.gradle.kts` 9; `Harness.kt` (its own event handling); `README.md` 5 | The harness runs `Stratum.kt` and the header and target code. It does not run MinerService's pool path (`PoolEvents`, `poolWorkLoop`, `shares()`, the job-id map, `clean` handling, pause and resume, the limiter, cleanup on reconnect). It also uses a different org.json from Android's (nesting limit, numbers parsed as `BigDecimal`). The README's "tried only on a computer, against the pool's own code" sounds broader than what actually ran. | H1, L1, L3 and M1 all live in the code the harness never runs, or behave differently under its parser. | Move the pool logic of `MinerService` into a class without Android code, behind an interface that stands in for the native library, and drive it from the harness: fake workers that respect job ids, plus pause and resume. Run the harness with Android's org.json; for example `com.vaadin.external.google:android-json` is the AOSP parser packaged for the JVM (check that it is the AOSP code before relying on it). Add negative cases: deep nesting, U+2028, notify before authorize, alternating seeds. |

### Notes

| ID | File and line | Observation |
|---|---|---|
| N1 | `Stratum.kt` 279-281, 373-377; `MinerService.kt` 493 | `if (!c.authorized)` can never be false: the `finally` block in `run()` calls `close()`, which resets `authorized` (T4). So every `StratumError` waits at least 60 s before reconnecting, including errors after mining had begun, and the comment "refused before mining" does not describe what happens. The effect is small. Keep "was authorized" in a field that `close()` does not reset. |
| N2 | `Stratum.kt` 380-393 | `line()` does not clear its buffer when it skips a blank line. In T4, 70,000 lines holding one space each ended the connection as "too long a line". |
| N3 | `MinerService.kt` 537-542 | When a clean job is replaced in `poolLatest` before the workers get it, its `clean` flag is lost. Old-tip jobs then stay in `poolJobs`, and nonces found for them in the last 250 ms are sent and refused as stale. Record "a clean job came" under the lock. |
| N4 | `Stratum.kt` 324-327; `StratumMath.target` | Nothing checks whether a difficulty is sensible. At or below 2^-32 every hash is a share (T2), so a pool can make the phone send hundreds of submits a second. A huge difficulty means no share is ever found, and nothing tells the user. Warn when the expected time per share at the measured rate is under a second or over a day. |
| N5 | `MinerService.kt` 94, 570; `Stratum.kt` 364-370 | Writes have no timeout. There is a single submitter thread with an unbounded queue. If the pool stops reading, the submitter blocks until the 300 s read timeout closes the socket. In practice this is bounded by the 256 pending shares unless the pool answers without reading. |
| N6 | `Stratum.kt` 338, 96, 88-89 and 120; `README.md` 35 | Compatibility limits are not stated. An empty extranonce1 makes the client ignore every job without saying anything. Each coinbase half over 4 KiB ends the connection. The time is sent back in lower case, although the comment says it is sent unchanged. Difficulty must be a JSON number. "Any pool … will do" has been tried with one pool. |
| N7 | `MinerService.kt` 130, 138 | On Stop, `onDestroy` closes the connection first, so shares still queued fail in `submit()`. The comment "what was found is sent" is true only in solo mode. |
| N8 | `MinerService.kt` 671, 374-380, 529 | Status texts. During a key rebuild the status says "waiting for work from the pool", because `preparedKey` keeps the old key until the build ends (0.1.2 review L12(b), repeated). After a failed preparation the app closes the connection itself, so "cannot mine: …" becomes "waiting for the pool: Socket closed" and the log blames the pool. When the pool sends no height, the log says "RandomX key from block -1". `preparedKey` is read on the control thread without `@Volatile`, which is harmless here. |
| N9 | `MinerService.kt` 525-533 | A permanent preparation failure, such as "the fast and the light mode disagree", is retried every ~30 s plus a full rebuild, forever, while the screen says "cannot mine". Solo mode does the same every 5 s. Stop after a few attempts. |
| N10 | `MinerService.kt` 437-448, 540-542 | Jobs are parsed and hashed also during a pause, with no rate limit. If more than about 32 jobs a second arrive, found shares are dropped from `poolJobs` (8 entries) before the 250 ms pass collects them. |
| N11 | `Stratum.kt` 100-118 | The Czech messages contain English field names, for example "pool poslal práci bez: previous block". |
| N12 | `README.md` 9, 37 | The first bullet of "What it does" describes solo mode only. PPS, the 5 % fee and the 1 NORTH minimum match the pool's code defaults (`pool.py` 375, `payout.py` 100). "Every ten minutes" depends on the pool's `--pay-every` setting (default 0, no payouts) and cannot be checked from the code. |
| N13 | `MinerService.kt` 386-387 (0.2.0: 286, 294) | Solo regression, negligible: at a key change, `client.blockHash()` now runs before the workers are stopped. If `getblockhash` fails, the previous template, already stale, is mined for up to 90 s, where 0.2.0 stopped. |

## Detail on H1, M1 and M2

**H1, evidence.**

- Android's org.json is the AOSP libcore implementation. `JSONTokener.nextValue()` calls `readArray()` or `readObject()`, which call `nextValue()` again, with no depth counter and no configuration for one. I could not run it here.
- A 64 KiB line allows about 65,000 levels. A thread made with `Thread(runnable, name)` gets about 1 MiB of stack on Android, so even small frames overflow at a few thousand levels.
- The stratum thread is started without its own handler (`MinerService.kt` 257). The app sets no default uncaught-exception handler (grep finds none). On Android an uncaught throwable on any thread ends the process.
- The JVM harness uses org.json 20240303. That version rejects the same input with a `JSONException` (T1), so the test passes. This is a harness blind spot, not evidence that the app is safe.
- Not reproduced on a device; my confidence comes from the code and the platform's parser.

**M1, numbers.** The simulation (a short script of my own, not kept) follows lines 424-435 exactly: the first key is free, every differing seed is appended, entries older than 30 minutes are dropped, more than 3 throws `PoolMisbehaves`, and the next connection comes 10 minutes later.

- If the pool changes the key 0.5, 3 or 5 minutes after each change the client accepts, the phone does 5 builds in the first hour and then 22, 20 or 19 builds in the following 11 hours.
- None of these builds looks at `paused`.

**M2, path.**

1. `MinerActivity.toggle()` stores the user and password fields under the same keys whatever the URL.
2. `run()` reads `user` as the worker name (247) and `password` as the pool password (253).
3. `StratumClient.run()` sends both in `mining.authorize` on a plain `Socket` (272).

The README's own node setup suggests the user name `phoneminer`, which passes the worker-name check.

## What is done well

- **Byte-level agreement with the pool, derived independently.** `StratumMath.header76`, `previous`, `nonceHex` and `target` reproduce `Job.header` and `share_target` exactly. Above 2^128 the target is clamped instead of overflowing. The share target is taken as ⌊2^96/d⌋ with exact rational arithmetic, so it also matches IronMiner for difficulties that are not powers of two (T2).
- **Bounds on everything parsed from the pool, except JSON nesting depth.**
  - line length (64 KiB);
  - job id (printable ASCII, at most 64 characters);
  - coinbase halves (4 KiB each);
  - branch (at most 32 entries of exactly 32 bytes);
  - version, bits and time (exactly 8 hex digits);
  - extranonce1 (at most 16 bytes) and extranonce2 size (1 to 8);
  - key (exactly 64 hex digits);
  - difficulty (finite and positive);
  - pending shares (256, with expiry);
  - pool text (at most 200 characters).
- **Each job carries its own target, key and connection.** The difficulty and key are fixed when the job arrives, which matches the stratum spec and the pool. Every job gets a fresh extranonce2, so a job sent twice never produces the same header twice.
- **Connections are kept apart.** When a connection ends, the job map is cleared, the workers are stopped, and `submit()` refuses on a closed client. No share can go out on a later connection.
- **The reader is never stalled by a dataset build.** The reader thread only records events; a separate thread does the slow work. The lock is never held across a native `prepare`, and I found no lock-order problem between the Kotlin lock and the native mutexes.
- **The lifecycle holds up.** Stop during a build waits for it. All three threads are joined before `Native.stop()` and `Native.release()`. A new start waits for the old cleanup. The UI cannot start a run while the old one is shutting down, because `running` stays true until the cleanup ends.
- **Reconnects back off sensibly.** The wait starts at 5 s and doubles to 120 s, resets after a connection that lasted, is at least 60 s after a refusal and 10 minutes after key abuse. Having a key-change limiter at all is the right idea.
- **Solo mode is unchanged.** `blocks()` is the old loop moved, and the status and notification texts are identical.
- **Useful tests and an honest README.** The harness checks the pool's ledger and its refusal codes (anything but 21 fails), which would catch any byte-order mistake. The README is candid that the pool mode has not run on a phone, that no one independent has read it, and that the author runs the Polarka pool.
