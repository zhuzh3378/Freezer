package com.watchfridge.freezer.core

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.watchfridge.freezer.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程内运行日志：环形缓冲 + 导出。
 *
 * 手表上没地方当场看 logcat，而远程判断「为什么这一次没冻上」只需要几行证据：
 * 授权探测的结论、每条 pm 命令的退出码与耗时、开关操作的落点。
 * 这些都往这里写一行，同时镜像到 logcat（tag = Freezer），两条取法：
 *
 *   adb logcat -d -s Freezer:*
 *   设置 → 导出日志，再 adb pull 界面里那行路径
 *
 * 导出文件名固定，pull 命令不用每次改；缓冲只活在本进程内，
 * 所以要在出问题的那一次之后、杀进程之前导出。
 */
object RunLog {

    const val TAG = "Freezer"

    /** 导出文件的固定名字。 */
    const val FILE_NAME = "freezer-last.log"

    private const val MAX_LINES = 2000
    private const val MAX_LEN = 240

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val clockTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val startedAt = System.currentTimeMillis()

    fun d(message: String) = append('D', message)

    fun w(message: String) = append('W', message)

    private fun append(level: Char, message: String) {
        val text = message.replace('\n', ' ')
        val trimmed = if (text.length > MAX_LEN) text.take(MAX_LEN) + "…" else text
        val line = synchronized(lock) {
            val stamped = "$level ${clockTime.format(Date())} $trimmed"
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(stamped)
            stamped
        }
        when (level) {
            'W' -> Log.w(TAG, line)
            else -> Log.d(TAG, line)
        }
    }

    /**
     * 把缓冲连同设备/设置快照写进导出文件。
     *
     * @param snapshot 调用方给的状态行（权限来源、隐藏图标、跟踪列表等）
     * @return 写成的文件；写失败返回 null，由调用方提示
     */
    fun export(context: Context, snapshot: List<String>): File? {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val text = synchronized(lock) {
            val head = buildList {
                add("Freezer ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
                add("${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
                add(
                    "pid=${Process.myPid()} started=${clockTime.format(Date(startedAt))} " +
                        "locale=${Locale.getDefault()}"
                )
                addAll(snapshot)
                add("--- log (${lines.size} lines) ---")
            }
            (head + lines).joinToString("\n")
        }
        return runCatching {
            val file = File(dir, FILE_NAME)
            file.writeText(text, Charsets.UTF_8)
            d("log exported: ${file.absolutePath} (${file.length()} bytes)")
            file
        }.onFailure {
            w("log export failed: ${it.message}")
        }.getOrNull()
    }
}
