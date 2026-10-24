package com.watchfridge.freezer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 全应用共享的 Preferences DataStore 实例。 */
val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** 权限来源。 */
enum class PrivilegeMode(val key: String) {
    SHIZUKU("shizuku"),
    ROOT("root");

    companion object {
        fun fromKey(key: String?): PrivilegeMode =
            entries.firstOrNull { it.key == key } ?: SHIZUKU
    }
}

/**
 * 设置仓库：权限来源、主界面跟踪与置顶的应用列表。
 * 全部持久化在 Preferences DataStore。
 *
 * 界面语言不在这里：它要在第一个 Activity 之前同步读到，见 `LocaleManager`。
 */
class SettingsRepository(private val context: Context) {

    private val dataStore = context.settingsDataStore

    val privilegeMode: Flow<PrivilegeMode> =
        dataStore.data.map { PrivilegeMode.fromKey(it[KEY_PRIVILEGE]) }

    /** 主界面快捷冻结列表（跟踪的包名集合）。 */
    val trackedPackages: Flow<Set<String>> =
        dataStore.data.map { prefs ->
            readPackageSet(prefs[KEY_TRACKED])
        }

    /** 主界面被长按置顶的包名集合；置顶区内按应用名排序，所以这里不记顺序。 */
    val pinnedPackages: Flow<Set<String>> =
        dataStore.data.map { prefs ->
            readPackageSet(prefs[KEY_PINNED])
        }

    /**
     * 上一次「实测通过」的结论（真的绑上特权服务并跑通命令）。
     * 只用来决定启动时先显示什么，后台仍会重新确认并纠正。
     */
    val shizukuVerified: Flow<Boolean> =
        dataStore.data.map { it[KEY_SHIZUKU_VERIFIED] ?: false }

    suspend fun setShizukuVerified(verified: Boolean) {
        dataStore.edit { it[KEY_SHIZUKU_VERIFIED] = verified }
    }

    suspend fun setPrivilegeMode(mode: PrivilegeMode) {
        dataStore.edit { it[KEY_PRIVILEGE] = mode.key }
    }

    suspend fun addTracked(packageName: String) {
        dataStore.edit { prefs ->
            val set = readPackageSet(prefs[KEY_TRACKED]) + packageName
            prefs[KEY_TRACKED] = set.joinToString(",")
        }
    }

    suspend fun removeTracked(packageName: String) {
        dataStore.edit { prefs ->
            val set = readPackageSet(prefs[KEY_TRACKED]) - packageName
            prefs[KEY_TRACKED] = set.joinToString(",")
            // 不再跟踪了，置顶也就没有意义：留着的话下次加回来会莫名排到最前
            val pinned = readPackageSet(prefs[KEY_PINNED]) - packageName
            prefs[KEY_PINNED] = pinned.joinToString(",")
        }
    }

    /** 置顶开关：已置顶就取消，否则加上；一次写回，避免连点时读到中间态。 */
    suspend fun togglePinned(packageName: String) {
        dataStore.edit { prefs ->
            val current = readPackageSet(prefs[KEY_PINNED])
            val next = if (packageName in current) current - packageName else current + packageName
            prefs[KEY_PINNED] = next.joinToString(",")
        }
    }

    private fun readPackageSet(raw: String?): Set<String> =
        (raw ?: "").split(',').filter { it.isNotBlank() }.toSet()

    companion object {
        val KEY_PRIVILEGE = stringPreferencesKey("privilege_mode")
        val KEY_TRACKED = stringPreferencesKey("tracked_packages")
        val KEY_PINNED = stringPreferencesKey("pinned_packages")
        val KEY_SHIZUKU_VERIFIED = booleanPreferencesKey("shizuku_verified")
    }
}
