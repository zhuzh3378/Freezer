package com.watchfridge.freezer

import android.app.Application
import android.content.Context
import com.watchfridge.freezer.core.LocaleManager

/**
 * 应用入口。
 *
 * attachBaseContext 里先同步读回语言，再按它包装 Context——
 * 第一个 Activity 创建前语言必须已经定下来，异步存储（DataStore）来不及。
 */
class FridgeApplication : Application() {

    override fun attachBaseContext(base: Context) {
        LocaleManager.mode = LocaleManager.load(base)
        super.attachBaseContext(LocaleManager.wrap(base))
    }
}
