package com.watchfridge.freezer.core

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/** 一次冻结/解冻操作的结果。 */
data class FreezeResult(val success: Boolean, val message: String)

/**
 * 冻结执行器。按设置的权限来源分发：
 *
 * - Shizuku：命令直接在 Shizuku 服务端（shell 或 root 进程）里 fork 执行——
 *   `Shizuku.getBinder()` → `IShizukuService.newProcess(["sh","-c",cmd])`，
 *   读回 stdout/stderr 与退出码。
 *   早先这里走 `bindUserService`：我们自己起一个 `:freeze` 子进程，代价是每次冷启动要等
 *   3.5~28 秒绑定（超时得设到 60 秒）、要防「管理器应用的中转 provider 找不到」那一类失败、
 *   还要处理绑定中途宿主进程被系统杀掉导致操作无声丢失。换成 newProcess 之后这些都不存在：
 *   命令落在服务端已有的进程里，实测几十到几百毫秒。
 * - Root：`su -c` 执行命令。
 *
 * 方法都是阻塞的（binder 事务 + 读管道），调用方必须在 IO 线程上。
 */
class FreezeManager {

    /** 探测链路能不能真的执行命令：跑一条 `id`。未授权时服务端会抛 SecurityException。 */
    fun probe(): Boolean {
        val began = SystemClock.elapsedRealtime()
        val result = run("id")
        RunLog.d("probe -> ${result.success} in ${SystemClock.elapsedRealtime() - began}ms")
        return result.success
    }

    fun setEnabledShizuku(packageName: String, enable: Boolean): FreezeResult =
        run(if (enable) "pm enable $packageName" else "pm disable-user --user 0 $packageName")

    /** Root 方式：`su -c` 执行命令。 */
    fun setEnabledRoot(packageName: String, enable: Boolean): FreezeResult {
        val cmd = if (enable) "pm enable $packageName" else "pm disable-user --user 0 $packageName"
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val exit = process.waitFor()
            FreezeResult(exit == 0, listOf(out, err).filter { it.isNotBlank() }.joinToString("\n"))
        }.getOrElse { FreezeResult(false, it.message ?: "root unavailable") }
    }

    /**
     * 在服务端执行一条命令，返回「退出码 + 合并输出」。
     *
     * 两条流要先读完再 `waitFor`：子进程输出多了会被管道缓冲区堵住，
     * 先等退出码就是死锁。
     */
    private fun run(command: String): FreezeResult {
        val began = SystemClock.elapsedRealtime()
        val result = runCatching {
            val binder = Shizuku.getBinder()
                ?: return@runCatching FreezeResult(false, "没连上 Shizuku 服务端")
            val service = IShizukuService.Stub.asInterface(binder)
                ?: return@runCatching FreezeResult(false, "binder 不是 IShizukuService，版本可能不匹配")
            val process = service.newProcess(arrayOf("sh", "-c", command), null, null)
            val out = process.inputStream?.readPfd().orEmpty()
            val err = process.errorStream?.readPfd().orEmpty()
            val exit = process.waitFor()
            process.destroy()
            FreezeResult(exit == 0, listOf(out, err).filter { it.isNotBlank() }.joinToString("\n").trim())
        }.getOrElse {
            FreezeResult(false, describe(it))
        }
        RunLog.d(
            "$command -> ${if (result.success) "exit=0" else "失败"} " +
                "in ${SystemClock.elapsedRealtime() - began}ms ${result.message.take(120)}"
        )
        return result
    }

    private fun describe(t: Throwable): String = when (t) {
        is SecurityException -> "Shizuku 未授权给本应用"
        else -> "${t.javaClass.simpleName}: ${t.message?.take(120) ?: "(无信息)"}"
    }

    private fun ParcelFileDescriptor.readPfd(): String =
        ParcelFileDescriptor.AutoCloseInputStream(this)
            .use { it.readBytes().toString(Charsets.UTF_8) }
}
