package com.watchfridge.freezer.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 伪装模式：图标换成耳机应用的图，名称换成「耳机控制器」，打开后先看到一块
 * 「请连接到手机后继续」的假门扉页，长按两次才进真正的主界面。
 *
 * 图标与名称靠切换启动器入口实现——`.LauncherNormal` 和 `.LauncherMasked` 两个
 * activity-alias 指向同一个 `.MainActivity`，哪个 enabled，启动器就按哪个的
 * label/icon 显示，不用重装也不用改包名。
 *
 * 开关存在 SharedPreferences 并同步读：门扉页必须在第一帧就是伪装的样子，
 * 走 DataStore（首读实测 0.1~2.9 秒）会先把真界面闪出来。
 */
object Masquerade {

    private const val PREF_STORE = "masquerade"
    private const val PREF_KEY = "enabled"

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(PREF_KEY, false)

    /** 落库 + 立刻切组件。启动器多久重读一次不由我们定，界面上要老实说「重开应用再看」。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_KEY, enabled).apply()
        applyLauncher(context, enabled)
    }

    /**
     * 把组件状态对齐到开关值。
     *
     * 切换组件是跨进程的写操作，每次冷启动都发一遍会让启动器反复刷新条目；
     * 先读现值，不一致才写——正常路径下这里什么都不做。
     */
    fun syncLauncher(context: Context) = applyLauncher(context, isEnabled(context))

    private fun applyLauncher(context: Context, enabled: Boolean) {
        val pm = context.packageManager
        val on = ComponentName(context.packageName, launcherName(context, enabled))
        val off = ComponentName(context.packageName, launcherName(context, !enabled))
        runCatching {
            // 先启后停：中间态是「两个都在」，不会是「一个都没有」，不至于把入口弄丢
            if (pm.getComponentEnabledSetting(on) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(
                    on, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
                )
            }
            if (pm.getComponentEnabledSetting(off) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(
                    off, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
                )
            }
        }
    }

    /** alias 的类名写成 `.LauncherXxx`，实际组件名是包名限定后的那个。 */
    private fun launcherName(context: Context, enabled: Boolean): String =
        context.packageName + "." + if (enabled) "LauncherMasked" else "LauncherNormal"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREF_STORE, Context.MODE_PRIVATE)
}
