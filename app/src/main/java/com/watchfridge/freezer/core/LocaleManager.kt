package com.watchfridge.freezer.core

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** 应用语言模式。 */
enum class LanguageMode(val key: String) {
    /** 跟随系统：系统语言为中文（含繁体）时显示中文，否则英文。 */
    AUTO("auto"),
    ZH("zh"),
    EN("en");

    companion object {
        fun fromKey(key: String?): LanguageMode =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * 语言管理器。
 *
 * - [mode] 为进程内缓存，默认 AUTO；语言必须在第一个 Activity 的 attachBaseContext
 *   之前就定下来，所以它落在 SharedPreferences 里由 [load] 同步读回——
 *   DataStore 的读是异步的，真机首读实测 0.1~2.9 秒，等不到。
 * - [wrap] 用 createConfigurationContext 生成目标语言的 Context，
 *   在 Application.attachBaseContext 中应用。
 */
object LocaleManager {

    private const val PREF_STORE = "freezer_locale"
    private const val PREF_KEY = "language"

    @Volatile
    var mode: LanguageMode = LanguageMode.AUTO

    /**
     * 同步读回上次选的语言；没读过就按 AUTO。
     *
     * 只用传进来的 Context 本身：Application.attachBaseContext 阶段 applicationContext 还是 null，
     * 走那一跳会在进程启动时就抛 NPE。
     */
    fun load(context: Context): LanguageMode = LanguageMode.fromKey(
        context.getSharedPreferences(PREF_STORE, Context.MODE_PRIVATE).getString(PREF_KEY, null)
    )

    /** 写入选择；apply() 异步落盘但立即更新内存快照，下一次启动读得到。 */
    fun save(context: Context, mode: LanguageMode) {
        context.getSharedPreferences(PREF_STORE, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY, mode.key)
            .apply()
    }

    /** 将语言模式解析为具体 Locale。 */
    fun resolve(mode: LanguageMode = this.mode): Locale {
        return when (mode) {
            LanguageMode.ZH -> Locale.SIMPLIFIED_CHINESE
            LanguageMode.EN -> Locale.ENGLISH
            LanguageMode.AUTO -> {
                val system = Locale.getDefault()
                // 系统语言是中文（简/繁）就保持中文资源，否则英文
                if (system.language.equals("zh", ignoreCase = true)) system
                else Locale.ENGLISH
            }
        }
    }

    /** 用目标语言包装 Context。 */
    fun wrap(base: Context): Context {
        val locale = resolve()
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }
}
