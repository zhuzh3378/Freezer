package com.watchfridge.freezer.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.watchfridge.freezer.core.RunLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** 无缓存时按这个条数发布渐进快照，让首次扫描也能逐步显示。 */
private const val CHUNK = 40

/**
 * 缓存行字段数：包名|是否系统|是否启用|安装路径|能否在启动器显示|应用名。
 * 应用名固定是最后一段（split 到 limit 为止，剩下的整体归它），所以标签里带 '|' 也不会解析错。
 */
private const val FIELD_COUNT = 6

/** 排序：第三方在前，同类按名字不区分大小写。 */
private val BY_TYPE_THEN_NAME: Comparator<AppEntry> =
    compareBy<AppEntry>({ it.isSystem }).then { a, b ->
        String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label)
    }

private fun systemFlagOf(info: ApplicationInfo): Boolean =
    (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0

private val Context.appsCacheStore by preferencesDataStore(name = "apps_cache")

private val KEY_APPS = stringPreferencesKey("apps_v3")
private val KEY_FIRMWARE = stringPreferencesKey("firmware")

/**
 * 缓存里的一行：应用名 + 判断它要不要重新取名的依据。
 *
 * 取名字要读对方包的资源，是整条链路上唯一贵的一步（300+ 个应用逐个 loadLabel
 * 实测 5～19 秒），而名字只会随 APK 换版变——每次安装都会换掉 /data/app 下那个
 * 随机段路径，所以拿 ApplicationInfo 自带的 sourceDir 一比就够了，不用额外跨进程
 * 调用。系统应用的 sourceDir 不动，但它们的标签只随 OTA 变，由固件标识整体作废。
 */
private class CachedApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val enabled: Boolean,
    val installPath: String,
    val launchable: Boolean,
) {
    fun toEntry() = AppEntry(packageName, label, isSystem, enabled, launchable)
}

/**
 * 应用仓库：枚举已安装应用、读取启用状态，并把结果落盘。
 */
class AppsRepository(private val context: Context) {

    /**
     * 先给缓存（可能为空），再扫 PackageManager 给最新结果。
     *
     * 有缓存时不再发渐进快照：中间态是乱序的，会让列表反复重排。
     * 冻结状态（enabled）每次都从 ApplicationInfo 现取，不受缓存影响；
     * 图标不在这里处理，由列表项按可见性单独取（见 AppIcon）。
     */
    fun stream(): Flow<List<AppEntry>> = flow {
        val began = SystemClock.elapsedRealtime()
        val cached = readCache()
        val cachedEntries = cached.map(CachedApp::toEntry)
        if (cached.isNotEmpty()) emit(cachedEntries)

        val byPkg = cached.associateBy { it.packageName }
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(0)
            .filter { it.packageName != context.packageName }
        val launchable = launchablePackages(pm)

        val entries = ArrayList<AppEntry>(installed.size)
        val fresh = ArrayList<CachedApp>(installed.size)
        var relabeled = 0
        installed.forEachIndexed { index, info ->
            val old = byPkg[info.packageName]
            val label = if (old != null && old.installPath == info.sourceDir) {
                old.label
            } else {
                relabeled++
                info.loadLabel(pm).toString()
            }
            val isSystem = systemFlagOf(info)
            val canLaunch = info.packageName in launchable
            entries += AppEntry(info.packageName, label, isSystem, info.enabled, canLaunch)
            fresh += CachedApp(
                info.packageName, label, isSystem, info.enabled, info.sourceDir, canLaunch,
            )
            if (cached.isEmpty() && (index + 1) % CHUNK == 0) {
                entries.sortWith(BY_TYPE_THEN_NAME)
                emit(entries.toList())
            }
        }

        entries.sortWith(BY_TYPE_THEN_NAME)
        val result = entries.toList()
        if (result != cachedEntries) emit(result)
        writeCache(fresh)
        RunLog.d(
            "apps scanned=${result.size} cached=${cached.size} relabeled=$relabeled " +
                "in ${SystemClock.elapsedRealtime() - began}ms"
        )
    }.flowOn(Dispatchers.IO)

    /** 批量重新读取启用状态（进入主界面时校正外部改动）。 */
    suspend fun refreshEnabled(entries: List<AppEntry>): List<AppEntry> =
        withContext(Dispatchers.IO) {
            entries.map { entry -> entry.copy(enabled = readEnabled(entry)) }
        }

    /** 重新读取单个应用的启用状态（一次开关之后只需要更新一行）。 */
    suspend fun refreshEnabled(entry: AppEntry): AppEntry = withContext(Dispatchers.IO) {
        entry.copy(enabled = readEnabled(entry))
    }

    private fun readEnabled(entry: AppEntry): Boolean = runCatching {
        context.packageManager.getApplicationInfo(entry.packageName, 0).enabled
    }.getOrDefault(entry.enabled)

    private suspend fun readCache(): List<CachedApp> = runCatching {
        val prefs = context.appsCacheStore.data.first()
        // 刷过机：系统应用的标签可能已经换了，而它们的安装路径没动，只能整份作废
        if (prefs[KEY_FIRMWARE] != Build.DISPLAY) return emptyList()
        val raw = prefs[KEY_APPS] ?: return emptyList()
        raw.split('\n').mapNotNull { line ->
            val fields = line.split('|', limit = FIELD_COUNT)
            if (fields.size < FIELD_COUNT) return@mapNotNull null
            CachedApp(
                packageName = fields[0],
                isSystem = fields[1] == "1",
                enabled = fields[2] == "1",
                installPath = fields[3],
                launchable = fields[4] == "1",
                label = fields[5],
            )
        }
    }.getOrDefault(emptyList())

    private suspend fun writeCache(entries: List<CachedApp>) = runCatching {
        context.appsCacheStore.edit { prefs ->
            prefs[KEY_FIRMWARE] = Build.DISPLAY
            prefs[KEY_APPS] = entries.joinToString("\n") {
                "${it.packageName}|${if (it.isSystem) 1 else 0}|${if (it.enabled) 1 else 0}|" +
                    "${it.installPath}|${if (it.launchable) 1 else 0}|${it.label.replace('\n', ' ')}"
            }
        }
    }

    /**
     * 有启动器入口的包名集合。
     *
     * 带上 `MATCH_DISABLED_COMPONENTS`：被我们自己冻掉的包，结构上仍然「能在启动器显示」，
     * 不然这一组筛选就和「已启用/已禁用」重复了。真正想筛出来的是库里包、
     * 只挂 provider/服务的那种——它们本来就没有 LAUNCHER 入口。
     */
    private fun launchablePackages(pm: PackageManager): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
            .mapTo(HashSet()) { it.activityInfo.packageName }
    }.getOrDefault(emptySet())
}
