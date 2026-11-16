package org.polarka.miner

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The native side: RandomX from the TrueNorth sources, see bench.cpp. */
object Native {
    init { System.loadLibrary("polarkaminer") }
    @JvmStatic external fun prepare(key: ByteArray?, fast: Boolean, threads: Int): String
    @JvmStatic external fun selfHash(withDataset: Boolean): String
    @JvmStatic external fun hashHeader(header80: ByteArray): String
    @JvmStatic external fun meetsTarget(hash: ByteArray, target: ByteArray): Boolean
    @JvmStatic external fun start(cpus: IntArray, pipelined: Boolean)
    @JvmStatic external fun mineStart(cpus: IntArray)
    @JvmStatic external fun setJob(header76: ByteArray?, target: ByteArray?, id: Long)
    @JvmStatic external fun takeFound(): LongArray?
    @JvmStatic external fun hashes(): Long
    @JvmStatic external fun failed(): Int
    @JvmStatic external fun stop()
    @JvmStatic external fun release()
}

/**
 * Stage 0 of Polarka Miner: measures how fast this phone computes TrueNorth's proof of work, how warm it gets
 * and whether the 2 GiB the fast mode needs can be had. Nothing is mined and nothing leaves the phone:
 * the app has no network permission. The lines on the screen are meant to be copied and sent by hand.
 */
class MainActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private val text = StringBuilder()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    @Volatile private var cancel = false
    @Volatile private var busy = false
    @Volatile private var prepared = ""
    @Volatile private var best = IntArray(0)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 3, pad, pad) }
        column.addView(TextView(this).apply {
            textSize = 15f
            text = tr("Polarka Miner, benchmark. Nothing is mined and nothing leaves the phone. ", "Polarka Miner, měření. Nic se netěží a nic neodchází z telefonu. ") +
                tr("Keep the phone on the charger; the display stays on. At a battery temperature of 45 °C the benchmark stops by itself.", "Nech telefon na nabíječce, displej zůstane svítit. Při 45 °C baterie se měření samo zastaví.")
        })
        column.addView(button(tr("1. Short run (about 5 minutes)", "1. Krátké měření (asi 5 minut)")) { run(tr("short run", "krátké měření")) { quick() } })
        column.addView(button(tr("2. Endurance run (15 minutes)", "2. Měření výdrže (15 minut)")) { run(tr("endurance run", "měření výdrže")) { endurance() } })
        column.addView(button(tr("Stop", "Zastavit")) { cancel = true })
        column.addView(button(tr("Copy the results", "Zkopírovat výsledky")) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Polarka Miner", text.toString()))
            say(tr("copied to the clipboard", "zkopírováno do schránky"))
        })
        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(log) }
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)
        say(tr("ready; ", "připraveno; ") + device())
    }

    override fun onDestroy() {
        cancel = true
        Thread { Native.stop(); Native.release() }.start()
        super.onDestroy()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }

    private fun say(line: String) {
        val stamped = clock.format(Date()) + " " + line + "\n"
        android.util.Log.i("PolarkaMiner", line)
        runOnUiThread {
            text.append(stamped)
            log.text = text
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun run(name: String, work: () -> Unit) {
        if (busy) { say(tr("something is already running", "už něco běží")); return }
        busy = true
        cancel = false
        Thread {
            try {
                say(tr("---- $name: start; ", "---- $name: začátek; ") + state())
                work()
                say("---- $name: " + (if (cancel) tr("stopped", "zastaveno") else tr("done", "hotovo")) + "; " + state())
            } catch (problem: Throwable) {
                say(tr("error: $problem", "chyba: $problem"))
            } finally {
                Native.stop()
                busy = false
            }
        }.start()
    }

    // ---------------------------------------------------------------- what the phone is and how it feels

    /** Index and highest clock of every core, fastest first. */
    private fun cores(): List<Pair<Int, Long>> {
        val count = Runtime.getRuntime().availableProcessors()
        return (0 until count).map { i ->
            val khz = try { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() } catch (e: Exception) { 0L }
            i to khz
        }.sortedWith(compareByDescending<Pair<Int, Long>> { it.second }.thenByDescending { it.first })
    }

    private fun memory(): String {
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return tr("memory free ${info.availMem / 1048576} of ${info.totalMem / 1048576} MiB", "paměť volná ${info.availMem / 1048576} z ${info.totalMem / 1048576} MiB")
    }

    private fun device(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL else "?"
        val freq = cores().groupBy { it.second }.map { (khz, list) -> "${list.size}x ${khz / 1000} MHz" }.joinToString(", ")
        return tr("${Build.MANUFACTURER} ${Build.MODEL}, chip $soc, Android ${Build.VERSION.RELEASE}, cores: $freq; ${memory()}", "${Build.MANUFACTURER} ${Build.MODEL}, čip $soc, Android ${Build.VERSION.RELEASE}, jádra: $freq; ${memory()}")
    }

    private fun state(): String {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10.0
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val thermal = getSystemService(PowerManager::class.java).currentThermalStatus
        return tr("battery %.1f °C, %d %%, %s, thermal status %d", "baterie %.1f °C, %d %%, %s, tepelný stav %d").format(Locale.ROOT, temp, level, if (plugged) tr("charging", "nabíjí se") else tr("NOT charging", "NENÍ na nabíječce"), thermal)
    }

    // ---------------------------------------------------------------- measuring

    private fun pause(millis: Long) {
        val end = System.nanoTime() + millis * 1_000_000
        var n = 0
        while (!cancel && System.nanoTime() < end) {
            Thread.sleep(100)
            // every five seconds a look at the battery: measuring is not worth a hot battery
            if (++n % 50 == 0) {
                val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val t = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
                if (t >= 45.0) {
                    say(tr("stopping: the battery has reached %.1f °C", "zastavuji: baterie má %.1f °C").format(Locale.ROOT, t))
                    cancel = true
                }
            }
        }
    }

    /** Hashes a second over the given time, after a short warm-up. */
    private fun measure(cpus: IntArray, seconds: Int, pipelined: Boolean): Double {
        Native.start(cpus, pipelined)
        pause(3000)
        val h0 = Native.hashes()
        val t0 = System.nanoTime()
        pause(seconds * 1000L)
        val rate = (Native.hashes() - h0) / ((System.nanoTime() - t0) / 1e9)
        val failed = Native.failed()
        Native.stop()
        if (failed > 0) say(tr("warning: threads that could not start: $failed", "pozor: nepodařilo se spustit vlákna, počet: $failed"))
        return rate
    }

    private fun prepare(): Boolean {
        if (MinerState.running) { say(tr("mining is running; start the benchmark after you stop it", "běží těžba; měření spusť, až ji zastavíš")); return false }
        if (prepared.startsWith("ok: fast")) return true
        say(tr("preparing the fast mode (about 2.3 GiB of memory), this can take a minute or two; ${memory()}", "připravuji rychlý režim (asi 2,3 GiB paměti), může to trvat minutu až dvě; ${memory()}"))
        var answer = Native.prepare(null, true, Runtime.getRuntime().availableProcessors())
        say(answer + "; ${memory()}")
        if (!answer.startsWith("ok")) {
            say(tr("the fast mode failed, trying the light one (256 MiB), which is many times slower", "rychlý režim se nepodařil, zkouším lehký (256 MiB), ten je řádově pomalejší"))
            answer = Native.prepare(null, false, 1)
            say(answer)
        }
        prepared = answer
        if (!answer.startsWith("ok")) return false
        val light = Native.selfHash(false)
        val full = if (answer.startsWith("ok: fast")) Native.selfHash(true) else light
        say(tr("check hash: $light", "kontrolní hash: $light") + if (light == full) tr(" (the light and the fast mode agree)", " (lehký i rychlý režim se shodují)") else tr(" WARNING, the fast mode gives $full", " POZOR, rychlý režim dává $full"))
        // The same input and key computed on a PC (x86, interpreter and code generator, software AES)
        // from the same sources. A phone that gives another value computes something else than the network.
        val expected = "d8833785120ddada94daa341facb4d612b6b0e8e5caed4aec44692138ab2c4d0"
        if (light != expected || full != expected) {
            say(tr("ERROR: the check hash differs from the reference value $expected. This phone computes RandomX differently from the network; a benchmark makes no sense.", "CHYBA: kontrolní hash nesouhlasí s referenční hodnotou $expected. Tento telefon počítá RandomX jinak než síť, měření nemá smysl."))
            return false
        }
        say(tr("the check hash agrees with the reference value from a PC", "kontrolní hash souhlasí s referenční hodnotou z PC"))
        return true
    }

    private fun quick() {
        if (!prepare()) return
        val order = cores()
        val count = order.size
        var top = 0.0
        for (n in (listOf(1, 2, 4, 6, 8).filter { it < count } + count)) {
            if (cancel) return
            val cpus = order.take(n).map { it.first }.toIntArray()
            val rate = measure(cpus, 20, false)
            say(tr("threads %d, fastest cores: %.0f H/s (%.0f per thread); %s", "počet vláken %d, nejrychlejší jádra: %.0f H/s (%.0f na vlákno); %s").format(Locale.ROOT, n, rate, rate / n, state()))
            if (rate > top) { top = rate; best = cpus }
        }
        if (cancel) return
        val free = measure(IntArray(count) { -1 }, 20, false)
        say(tr("threads %d, not pinned to cores: %.0f H/s; %s", "počet vláken %d, bez přiřazení k jádrům: %.0f H/s; %s").format(Locale.ROOT, count, free, state()))
        if (cancel) return
        val piped = measure(best, 20, true)
        say(tr("threads %d, chained computation: %.0f H/s (against %.0f); %s", "počet vláken %d, zřetězený výpočet: %.0f H/s (proti %.0f); %s").format(Locale.ROOT, best.size, piped, top, state()))
    }

    private fun endurance() {
        if (!prepare()) return
        if (best.isEmpty()) best = cores().map { it.first }.toIntArray()
        say(tr("endurance: threads ${best.size}, 15 minutes, a line every minute", "výdrž: počet vláken ${best.size}, 15 minut, hlášení každou minutu"))
        Native.start(best, false)
        pause(3000)
        var h0 = Native.hashes()
        var t0 = System.nanoTime()
        for (minute in 1..15) {
            pause(60_000)
            if (cancel) break
            val h1 = Native.hashes()
            val t1 = System.nanoTime()
            say(tr("minute %2d: %.0f H/s; %s", "%2d. minuta: %.0f H/s; %s").format(Locale.ROOT, minute, (h1 - h0) / ((t1 - t0) / 1e9), state()))
            h0 = h1
            t0 = t1
        }
        Native.stop()
    }
}
