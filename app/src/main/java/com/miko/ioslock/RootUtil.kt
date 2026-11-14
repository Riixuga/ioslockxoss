package com.miko.ioslock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Semua perintah root lewat sini. Pengaturan aplikasi diterapkan ke System UI dengan setprop
 * (modul membaca debug.ioslock.* tiap ~400 ms, jadi perubahan langsung terasa tanpa restart).
 */
object RootUtil {
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private class Result(val code: Int, val out: String)

    /** Satu shell su yang dibuka sekali dan dipakai terus, supaya manajer root tidak memunculkan izin/toast tiap perintah. */
    private class Shell(val p: Process) {
        val out: OutputStream = p.outputStream
        val lines = LinkedBlockingQueue<String>()

        init {
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { lines.offer(it) }
                } catch (_: Throwable) { }
            }.start()
            Thread {
                try {
                    p.errorStream.bufferedReader().forEachLine { }
                } catch (_: Throwable) { }
            }.start()
        }

        fun alive(): Boolean = try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private var shell: Shell? = null

    private fun getShell(): Shell? {
        val s = shell
        if (s != null && s.alive()) return s
        return try {
            val n = Shell(Runtime.getRuntime().exec("su"))
            shell = n
            n
        } catch (t: Throwable) {
            null
        }
    }

    // dijalankan hanya dari thread io (satu per satu)
    private fun exec(cmd: String, timeoutMs: Long): Result {
        val s = getShell() ?: return Result(-1, "")
        return try {
            s.lines.clear()
            val token = "__IOSL_" + System.nanoTime() + "_"
            s.out.write((cmd + "\necho " + token + "\$?\n").toByteArray())
            s.out.flush()
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            val sb = StringBuilder()
            var result = Result(-1, "")
            while (SystemClock.uptimeMillis() < deadline) {
                val line = s.lines.poll(200L, TimeUnit.MILLISECONDS)
                if (line == null) {
                    if (!s.alive()) break
                    continue
                }
                if (line.startsWith(token)) {
                    result = Result(line.substring(token.length).trim().toIntOrNull() ?: -1, sb.toString())
                    break
                }
                sb.append(line).append('\n')
            }
            if (result.code == -1 && !s.alive()) shell = null
            result
        } catch (t: Throwable) {
            shell = null
            Result(-1, "")
        }
    }

    private fun post(cb: ((Boolean) -> Unit)?, ok: Boolean) {
        if (cb != null) main.post { cb(ok) }
    }

    /** Jalankan perintah root; cb dipanggil di main thread (true = berhasil). */
    fun run(cmd: String, cb: ((Boolean) -> Unit)? = null) {
        io.execute {
            val r = exec(cmd, 10_000L)
            post(cb, r.code == 0)
        }
    }

    /** Cek apakah akses root diberikan. Pertama kali, manajer root akan menampilkan dialog izin. */
    fun check(cb: (Boolean) -> Unit) {
        io.execute {
            val r = exec("id", 30_000L)
            post(cb, r.code == 0 && r.out.contains("uid=0"))
        }
    }

    /** Matikan System UI; sistem langsung membangkitkannya lagi. */
    fun restartSystemUi(cb: ((Boolean) -> Unit)? = null) {
        run("killall com.android.systemui || pkill -f com.android.systemui", cb)
    }

    /** Terapkan pengaturan aplikasi ke property yang dibaca modul. */
    fun applyAll(ctx: Context, cb: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        val power = if (AppPrefs.powerBtn(app)) "1" else "0"
        val frac = AppPrefs.pillFrac(app)
        val pill = String.format(Locale.US, "%.4f", frac)
        // titik asal animasi buka dock (dipakai kalau pil dimatikan) ikut bergeser bersama pil
        val origin = String.format(Locale.US, "%.4f", frac + 0.01f)
        run(
            "setprop debug.ioslock.powerbtn $power; " +
                "setprop debug.ioslock.countfrac $pill; " +
                "setprop debug.ioslock.originfrac $origin",
            cb
        )
    }
}
