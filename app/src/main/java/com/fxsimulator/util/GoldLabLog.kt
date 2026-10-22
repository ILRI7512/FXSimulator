package com.fxsimulator.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量日志与崩溃记录。
 *
 * 为什么需要它：本机无法用 adb 安装调试，出了问题只能靠用户把日志带出来。
 * 因此所有日志写到**应用专属外部目录**（无需任何存储权限）：
 *   /storage/emulated/0/Android/data/com.fxsimulator/files/logs/
 * 该目录既能让用户通过「分享」发出来，也能被设备 shell 读取。
 *
 * 崩溃记录：安装全局未捕获异常处理器，把完整堆栈 + 设备信息落盘为
 * crash-<时间戳>.txt，然后交回系统原有处理器，不吞掉崩溃。
 */
object GoldLabLog {

    private const val TAG = "GoldLabLog"
    private const val LOG_NAME = "goldlab.log"
    private const val MAX_LOG_BYTES = 512L * 1024L

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var logDir: File? = null

    /** 必须在 Application.onCreate 中尽早调用 */
    fun init(context: Context) {
        appContext = context.applicationContext
        val base = try {
            context.getExternalFilesDir(null)
        } catch (e: Exception) {
            null
        } ?: context.filesDir
        val dir = File(base, "logs")
        if (!dir.exists()) dir.mkdirs()
        logDir = dir
        installCrashHandler()
        i(TAG, "=== GoldLab 启动 · " + appVersion() + " ===")
    }

    fun directory(): File? = logDir

    fun logFile(): File? = logDir?.let { File(it, LOG_NAME) }

    /** 全部日志文件，崩溃记录排在前面 */
    fun listLogs(): List<File> {
        val dir = logDir ?: return emptyList()
        val all = dir.listFiles()?.filter { it.isFile } ?: return emptyList()
        return all.sortedWith(
            compareByDescending<File> { it.name.startsWith("crash-") }
                .thenByDescending { it.lastModified() },
        )
    }

    fun crashFiles(): List<File> =
        listLogs().filter { it.name.startsWith("crash-") }

    fun read(file: File, maxBytes: Int = 200_000): String {
        return try {
            val text = file.readText()
            if (text.length > maxBytes) {
                "...（日志过长，仅显示最后 " + maxBytes + " 字符）...\n" +
                    text.substring(text.length - maxBytes)
            } else {
                text
            }
        } catch (e: Throwable) {
            "读取日志失败：" + e.message
        }
    }

    fun clear() {
        synchronized(lock) {
            try {
                logDir?.listFiles()?.forEach { it.delete() }
            } catch (_: Throwable) {
                // 忽略
            }
        }
        i(TAG, "日志已清空")
    }

    // ------------------------------------------------------------ 写日志

    fun i(tag: String, message: String) = write("I", tag, message)
    fun w(tag: String, message: String) = write("W", tag, message)

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        write("E", tag, message + (throwable?.let { "\n" + stackTraceOf(it) } ?: ""))
    }

    private fun write(level: String, tag: String, message: String) {
        val dir = logDir ?: return
        synchronized(lock) {
            try {
                val file = File(dir, LOG_NAME)
                if (file.length() > MAX_LOG_BYTES) {
                    val old = File(dir, LOG_NAME + ".1")
                    if (old.exists()) old.delete()
                    file.renameTo(old)
                }
                file.appendText(
                    timeFormat.format(Date()) + " " + level + "/" + tag + ": " + message + "\n",
                )
            } catch (_: Throwable) {
                // 日志本身绝不能成为崩溃源
            }
        }
    }

    // ------------------------------------------------------------ 崩溃

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = logDir
                if (dir != null) {
                    val file = File(dir, "crash-" + System.currentTimeMillis() + ".txt")
                    file.writeText(buildCrashReport(thread, throwable))
                    write("E", "CRASH", "已记录崩溃到 " + file.name)
                }
            } catch (_: Throwable) {
                // 记录崩溃失败也不能再抛
            }
            // 交回系统原有处理器：不吞掉崩溃
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val sb = StringBuilder()
        sb.append("GoldLab 崩溃报告\n")
        sb.append("时间: ").append(timeFormat.format(Date())).append("\n")
        sb.append("应用版本: ").append(appVersion()).append("\n")
        sb.append("线程: ").append(thread.name).append("\n")
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n")
        sb.append("Android: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append("\n")
        val rt = Runtime.getRuntime()
        sb.append("内存: 已用 ").append((rt.totalMemory() - rt.freeMemory()) / 1048576)
            .append(" MB / 上限 ").append(rt.maxMemory() / 1048576).append(" MB\n")
        sb.append("\n").append(stackTraceOf(throwable))
        return sb.toString()
    }

    fun stackTraceOf(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }

    private fun appVersion(): String {
        val ctx = appContext ?: return "unknown"
        return try {
            val pm = ctx.packageManager
            val info = pm.getPackageInfo(ctx.packageName, 0)
            info.versionName + " (" + info.longVersionCode + ")"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
