package com.watchfridge.freezer.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchfridge.freezer.R
import com.watchfridge.freezer.core.DisplayShape
import com.watchfridge.freezer.core.DisplayShapeStore
import com.watchfridge.freezer.core.FreezeManager
import com.watchfridge.freezer.core.FreezeResult
import com.watchfridge.freezer.core.LanguageMode
import com.watchfridge.freezer.core.LocaleManager
import com.watchfridge.freezer.core.Masquerade
import com.watchfridge.freezer.core.RunLog
import com.watchfridge.freezer.core.ShapeMode
import com.watchfridge.freezer.data.AppEntry
import com.watchfridge.freezer.data.AppsRepository
import com.watchfridge.freezer.data.PrivilegeMode
import com.watchfridge.freezer.data.SettingsRepository
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** 权限来源的当前可用性，用于设置页展示与操作前自检。 */
enum class PrivilegeStatus {
    /** 已实测通过：特权服务能绑上且能执行命令。 */
    SHIZUKU_READY,

    /** 已授权，但本进程没实测过。不猜结论，也不为它去绑服务。 */
    SHIZUKU_UNVERIFIED,

    /** 正在向特权服务确认，结果未出。 */
    SHIZUKU_PROBING,

    /** Shizuku 服务端根本没起来。 */
    SHIZUKU_NOT_RUNNING,

    /**
     * 服务端在跑（管理器应用在、provider 解析得到），但我们这个进程拿不到 binder。
     *
     * 真机见过：进程活了 19 小时没死，中间 Shizuku 管理器重启过，客户端手里那个 binder
     * 就成了死的，而客户端不会自己重新握手 —— `pingBinder()` 从此一直 false。
     * 这时候叫用户「去启动 Shizuku」是错的，该做的是重开本应用。
     */
    SHIZUKU_NOT_CONNECTED,

    /** 系统还没把权限给我们。 */
    SHIZUKU_PERMISSION_REQUIRED,

    /** 已授权但特权服务跑不起来（服务端刚启动、进程被回收等）。 */
    SHIZUKU_UNAVAILABLE,

    ROOT,
}

/**
 * 主界面 / 添加界面 / 设置界面共用的状态。
 *
 * - [apps]：全量应用列表（添加界面数据源）。
 * - [tracked]：主界面跟踪的包名集合（快捷冻结列表）。
 * - [trackedApps]：主界面列表 = apps ∩ tracked。
 * - [ready]：跟踪列表与全量列表都已完成首次加载，可用于区分
 *   “还在加载” 和 “真的还没添加应用”。
 * - [busy]：正在执行冻结/解冻的包名（防连点）。
 * - [lastResult]：最近一次操作结果。
 * - 设置项：权限来源 / 语言。
 */
class FreezerViewModel(application: Application) : AndroidViewModel(application) {

    private val appsRepo = AppsRepository(application)
    private val settings = SettingsRepository(application)
    // 无状态：命令交给 Shizuku 服务端执行，没有要活到界面销毁的连接或子进程
    private val freezeManager = FreezeManager()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val _appsLoaded = MutableStateFlow(false)
    private val _trackedLoaded = MutableStateFlow(false)
    val ready: StateFlow<Boolean> =
        combine(_appsLoaded, _trackedLoaded) { a, b -> a && b }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val tracked: StateFlow<Set<String>> =
        settings.trackedPackages
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** 主界面被长按置顶的包名；置顶区内按应用名排序，与冻结状态无关。 */
    val pinned: StateFlow<Set<String>> =
        settings.pinnedPackages
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val privilegeMode: StateFlow<PrivilegeMode> =
        settings.privilegeMode
            .stateIn(viewModelScope, SharingStarted.Eagerly, PrivilegeMode.SHIZUKU)

    /**
     * 界面语言。冷启动时 [LocaleManager] 已从 SharedPreferences 同步读回进程缓存，
     * 这里直接继承那个值——DataStore 首读实测要 0.1~2.9 秒，等不到第一个 Activity。
     */
    private val _languageMode = MutableStateFlow(LocaleManager.mode)
    val languageMode: StateFlow<LanguageMode> = _languageMode

    /** 伪装模式开关。同步读 SharedPreferences：门扉页要赶在第一帧之前定下来。 */
    private val _masquerade = MutableStateFlow(Masquerade.isEnabled(application))
    val masquerade: StateFlow<Boolean> = _masquerade

    /**
     * 屏幕形状：设置页选的档位，以及实际生效的形状。
     *
     * 形状决定列表用鱼眼还是平铺，所以它得能驱动重组——由 MainActivity 通过
     * `LocalDisplayShape` 发给各界面，不让每个界面各自去读一遍存储。
     */
    private val _shapeMode = MutableStateFlow(DisplayShapeStore.mode(application))
    val shapeMode: StateFlow<ShapeMode> = _shapeMode

    private val _displayShape = MutableStateFlow(DisplayShapeStore.resolve(application))
    val displayShape: StateFlow<DisplayShape> = _displayShape

    fun setShapeMode(mode: ShapeMode) {
        val context = getApplication<Application>()
        DisplayShapeStore.save(context, mode)
        _shapeMode.value = mode
        _displayShape.value = DisplayShapeStore.resolve(context, mode)
        RunLog.d("shape mode=$mode -> ${_displayShape.value}")
    }

    /**
     * 切换伪装模式：落库 + 换启动器入口（图标与名称）。
     *
     * 启动器什么时候重读那份 label/icon 不由我们定，所以调用方切完要重建界面，
     * 并老实告诉用户「重开应用再看」。
     */
    fun setMasquerade(enabled: Boolean) {
        val context = getApplication<Application>()
        Masquerade.setEnabled(context, enabled)
        _masquerade.value = enabled
        RunLog.d("masquerade -> $enabled")
    }

    /**
     * 主界面列表顺序的快照（包名序列）。
     *
     * 只在跟踪集合变化、应用列表首次扫完、以及回到主界面时重建，
     * 所以拨完开关不会让那一行当场跳到列表另一端。
     */
    private val homeOrder = MutableStateFlow<List<String>>(emptyList())

    /**
     * 主界面列表：已跟踪的应用，按 [homeOrder] 的快照顺序显示，
     * 长按置顶的那些排在最前，区内按应用名排序。
     *
     * 不按实时的冻结状态排：那样刚拨完开关，那一行就立刻跳走，
     * 手还没抬起来就点错了下一行。置顶则相反——它就是要越过冻结状态，
     * 所以 [rebuildHomeOrder] 的快照仍按原规则覆盖全部跟踪项，
     * 取消置顶的那一行会落回它本来的位置。
     */
    val trackedApps: StateFlow<List<AppEntry>> =
        combine(_apps, tracked, homeOrder, pinned) { apps, tracked, order, pinned ->
            val visible = apps.filter { it.packageName in tracked }
            val (pinnedRows, restRows) = visible.partition { it.packageName in pinned }
            val byPkg = restRows.associateBy { it.packageName }
            val known = order.mapNotNull { byPkg[it] }
            val fresh = restRows.filter { it.packageName !in order }.sortedWith(HOME_ORDER)
            pinnedRows.sortedWith(BY_LABEL) + known + fresh
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    /**
     * 添加界面的搜索与筛选条件：跨「添加」「筛选」两个界面共享。
     *
     * 合成一个对象而不是四个 StateFlow，是因为 [visibleApps] 要同时按这几项重算，
     * 拆开就得凑六个流去 combine。
     */
    data class Filters(
        val query: String = "",
        val type: TypeFilter = TypeFilter.ALL,
        val status: StatusFilter = StatusFilter.ALL,
        val launchable: LaunchableFilter = LaunchableFilter.ALL,
    )

    private val _filters = MutableStateFlow(Filters())
    val filters: StateFlow<Filters> = _filters

    fun setQuery(value: String) {
        _filters.update { it.copy(query = value) }
    }

    fun setTypeFilter(value: TypeFilter) {
        _filters.update { it.copy(type = value) }
    }

    fun setStatusFilter(value: StatusFilter) {
        _filters.update { it.copy(status = value) }
    }

    fun setLaunchableFilter(value: LaunchableFilter) {
        _filters.update { it.copy(launchable = value) }
    }

    /** 清空关键字与三组筛选，回到「全部应用」。 */
    fun resetFilters() {
        _filters.value = Filters()
    }

    /** 当前搜索与三组筛选匹配上的应用（不含顺序）。 */
    private fun matchedApps(): List<AppEntry> {
        val f = _filters.value
        return _apps.value.filter { app ->
            (f.query.isBlank() || app.label.contains(f.query, ignoreCase = true) ||
                app.packageName.contains(f.query, ignoreCase = true)) &&
                when (f.type) {
                    TypeFilter.ALL -> true
                    TypeFilter.THIRD_PARTY -> !app.isSystem
                    TypeFilter.SYSTEM -> app.isSystem
                } &&
                when (f.status) {
                    StatusFilter.ALL -> true
                    StatusFilter.ENABLED -> app.enabled
                    StatusFilter.DISABLED -> !app.enabled
                } &&
                when (f.launchable) {
                    LaunchableFilter.ALL -> true
                    LaunchableFilter.VISIBLE -> app.launchable
                    LaunchableFilter.HIDDEN -> !app.launchable
                }
        }
    }

    /**
     * 添加界面的顺序快照（包名序列）。
     *
     * 只在重新进入添加界面、以及筛选条件变了之后重建；勾选/取消不重建。
     * 否则一拨开关，那一行就带着视线跳到列表另一端，想接着往下滑还得从头再滑一遍
     * ——主界面为同一件事用过同一个办法。
     */
    private val _addOrder = MutableStateFlow<List<String>>(emptyList())

    /** 定一次当前列表的顺序：已勾选在前按名字，其余第三方在前、同类按名字。 */
    fun rebuildAddOrder() {
        val (picked, others) = matchedApps().partition { it.packageName in tracked.value }
        _addOrder.value = (picked.sortedWith(BY_LABEL) + others.sortedWith(BY_TYPE_THEN_NAME))
            .map { it.packageName }
    }

    /**
     * 添加界面显示的列表。
     *
     * 内容跟着搜索、筛选和全量扫描更新；顺序按 [_addOrder] 的快照走，
     * 所以拨开关不会让行跳位。快照里没有的（刚扫出来的新应用）按默认规则排在末尾。
     */
    val visibleApps: StateFlow<List<AppEntry>> =
        combine(_apps, _filters, _addOrder) { _, f, order ->
            val matched = matchedApps()
            if (order.isEmpty()) {
                val (picked, others) = matched.partition { it.packageName in tracked.value }
                picked.sortedWith(BY_LABEL) + others.sortedWith(BY_TYPE_THEN_NAME)
            } else {
                val rank = HashMap<String, Int>(order.size)
                order.forEachIndexed { index, pkg -> rank[pkg] = index }
                val (known, fresh) = matched.partition { it.packageName in rank }
                known.sortedBy { rank.getValue(it.packageName) } +
                    fresh.sortedWith(BY_TYPE_THEN_NAME)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _lastResult = MutableStateFlow<FreezeResult?>(null)
    val lastResult: StateFlow<FreezeResult?> = _lastResult

    /** 最近一次日志导出的结果：成功是文件绝对路径，失败是提示文案。 */
    private val _logExport = MutableStateFlow<String?>(null)
    val logExport: StateFlow<String?> = _logExport

    private val _privilegeStatus = MutableStateFlow(PrivilegeStatus.SHIZUKU_NOT_RUNNING)
    val privilegeStatus: StateFlow<PrivilegeStatus> = _privilegeStatus

    /** 上一次实测通过的结论：只决定首屏显示什么，实测仍留给真实操作或「验证」。 */
    private val _verified = MutableStateFlow(false)

    /** 「上次实测通过」是否已从磁盘读回来。读回来之前不响应 binder 回调：日志里它总比 DataStore 先到。 */
    private var verifiedRestored = false

    /** 显式验证单飞：一次 `id` 只要几十毫秒，但连点也不该并发出多条。 */
    private var verifyJob: Job? = null

    /** 预热每个进程只做一次（第一次回前台）：先跑一条 `id`，把首次调用的开销挪到没人等的时候。 */
    private var warmedUp = false

    /** Shizuku 授权对话框的回调：结果只改显示。 */
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, resultCode ->
            RunLog.d("permission dialog result=$resultCode")
            refreshPrivilegeStatus()
            // 服务端落库可能略晚于回调，稍后再问一次，避免误判成未授权
            viewModelScope.launch {
                delay(GRANT_SETTLE_MS)
                refreshPrivilegeStatus()
            }
        }

    /** 本进程是否拿到过 Shizuku 的 binder。没有它，`checkSelfPermission` 返回 false 只说明 binder 还没送到。 */
    private var binderSeen = false

    /** 连续读到「没授权」的次数，用来挡住管理器进程重启那一瞬的假阴性。 */
    private var ungrantedReads = 0

    /** Shizuku 服务端起来时重新走一遍轻量判定（缺授权由判定本身补拉对话框）。 */
    private val binderListener = Shizuku.OnBinderReceivedListener {
        RunLog.d("binder received")
        binderSeen = true
        if (verifiedRestored) refreshPrivilegeStatus()
    }

    /**
     * 服务端掉了。
     *
     * 少了这个回调，本地缓存的 binder 会一直「像是活的」：pingBinder 读的就是它，
     * 于是状态停在「已就绪」，而每条命令都失败——这正是「经常拿不到授权」的形状。
     */
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        RunLog.w("binder dead")
        binderSeen = false
        setStatus(PrivilegeStatus.SHIZUKU_NOT_RUNNING)
    }

    /**
     * 挂三个 Shizuku 回调。由 Activity 在 onStart 调，跟界面可见性走
     * （挂在 ViewModel 上会让它活到进程结束，界面重建时旧监听还在）。
     */
    fun registerShizukuListeners() {
        runCatching {
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        }
    }

    /** [registerShizukuListeners] 的对称操作，Activity onStop 调。 */
    fun unregisterShizukuListeners() {
        runCatching {
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        }
    }

    /** 自动请求只做一次：对话框关闭会让界面重新回到前台，重复请求会弹个没完。 */
    private var permissionRequested = false

    init {
        RunLog.d("view model created")
        load()
        viewModelScope.launch {
            settings.trackedPackages.first()
            _trackedLoaded.value = true
        }
        // 全量列表扫完 + 跟踪集合变化时重建主界面顺序快照；开关本身不触发重建
        viewModelScope.launch {
            _appsLoaded.collect { if (it) rebuildHomeOrder() }
        }
        viewModelScope.launch {
            tracked.collect { rebuildHomeOrder() }
        }
        viewModelScope.launch {
            // 先把「上次实测通过」读回来再下判断：binder 回调总比 DataStore 首读早到，
            // 抢跑那次会把已有结论当没有，首屏就会闪一句「未验证」。
            // （监听器由 Activity 在 onStart 挂，不在这里。）
            _verified.value = runCatching { settings.shizukuVerified.first() }.getOrDefault(false)
            RunLog.d("restored verified=${_verified.value}")
            verifiedRestored = true
            refreshPrivilegeStatus()
        }
    }

    override fun onCleared() {
        RunLog.d("view model cleared")
        // 没有要解绑的连接、也没有要收尾的子进程：命令都在 Shizuku 服务端里执行完就返回
        super.onCleared()
    }

    fun load() {
        viewModelScope.launch {
            try {
                appsRepo.stream().collect { _apps.value = it }
            } finally {
                // 渐进快照会多次发射，流结束才代表全量扫完
                _appsLoaded.value = true
                // 只预载主界面那十几个：整表 300+ 个图标意味着每次启动都付 300+ 次
                // 跨进程取图，而添加页本来就按可见性现取（见 AppIcon 的兜底路径）
                val trackedNow = tracked.value
                AppIconCache.preload(
                    getApplication(),
                    _apps.value.map { it.packageName }.filter { it in trackedNow },
                )
            }
        }
    }

    /**
     * 重新判定权限来源可用性——只做轻量判定：服务端在不在、授权到手没有、上次有没有实测通过。
     *
     * 两条红线：
     * - 不在这条路径上绑特权服务：一次绑定要 6~28 秒，冷启动 / 回到前台 / binder 回调都来一下的话，
     *   用户第一次拨开关就得排在它后面，而息屏后进程被系统杀掉，那次操作就无声丢了。
     * - 不在主线程做跨进程调用：`pingBinder` / `checkSelfPermission` 都是打到 Shizuku 管理器进程的
     *   binder 事务，而那块表上「我们」和「Shizuku」都会被 Freecess 冻结（真机日志：
     *   `FZ : com.watchfridge.freezer(10230), reason: LEV` 连着 `UFZ … reason: Cfb` 隔了 3.8 秒），
     *   对方冻住时事务被推迟，主线程就这么干等。这里先用本地缓存占位，结论回来再纠正。
     *
     * 要靠命令确认就走 [verifyNow]（设置页那枚「验证」），或者直接拨一次开关。
     */
    fun refreshPrivilegeStatus() {
        if (privilegeMode.value == PrivilegeMode.ROOT) {
            setStatus(PrivilegeStatus.ROOT)
            return
        }
        viewModelScope.launch(Dispatchers.IO) { judgePrivilege() }
    }

    /** [refreshPrivilegeStatus] 的跨进程部分，只在 IO 上跑。 */
    private suspend fun judgePrivilege() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            setStatus(
                if (relayResolvable()) PrivilegeStatus.SHIZUKU_NOT_CONNECTED
                else PrivilegeStatus.SHIZUKU_NOT_RUNNING
            )
            return
        }
        if (!shizukuGranted()) {
            // 授权都没了，「实测通过」的结论自然也不成立
            setVerified(false)
            setStatus(PrivilegeStatus.SHIZUKU_PERMISSION_REQUIRED)
            // 连读两次才拉对话框：binder 刚到、管理器进程正在重启这类瞬间，
            // checkSelfPermission 会假报未授权（真机日志里 23:21:43 就是这么一次）。
            // 前台轮询每 5 秒会再读一次，真缺授权对话框只会晚 5 秒出现。
            ungrantedReads++
            if (binderSeen && ungrantedReads >= 2) {
                withContext(Dispatchers.Main) { requestShizukuPermissionOnce() }
            }
            return
        }
        ungrantedReads = 0
        setStatus(
            if (_verified.value) PrivilegeStatus.SHIZUKU_READY
            else PrivilegeStatus.SHIZUKU_UNVERIFIED
        )
    }

    /** 设置页那行文案的唯一出口：变化时留一行日志，导出才知道结论是怎么翻的。 */
    private fun setStatus(status: PrivilegeStatus) {
        val previous = _privilegeStatus.value
        if (previous != status) RunLog.d("privilege status: $previous -> $status")
        _privilegeStatus.value = status
    }

    /**
     * 实测一次：让服务端跑一条 `id`。
     *
     * 这是唯一能说「就绪」的依据。代价只有几十毫秒，不像旧的 `bindUserService` 那样要
     * 躲着它；但仍然只在用户点「验证」时做——启动、回到前台、授权回调走轻量判定就够了。
     * 单飞：连点不并发发命令。
     */
    fun verifyNow() {
        if (privilegeMode.value == PrivilegeMode.ROOT) return
        if (verifyJob?.isActive == true) {
            RunLog.d("verify already running")
            return
        }
        setStatus(PrivilegeStatus.SHIZUKU_PROBING)
        verifyJob = viewModelScope.launch(Dispatchers.IO) {
            if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                setStatus(PrivilegeStatus.SHIZUKU_NOT_RUNNING)
                return@launch
            }
            if (!shizukuGranted()) {
                setVerified(false)
                setStatus(PrivilegeStatus.SHIZUKU_PERMISSION_REQUIRED)
                withContext(Dispatchers.Main) { requestShizukuPermissionOnce() }
                return@launch
            }
            if (freezeManager.probe()) markShizukuVerified()
            else {
                // 命令没跑通：「已就绪」这个结论当场作废，具体原因在 FreezeManager 的日志行里
                setVerified(false)
                setStatus(PrivilegeStatus.SHIZUKU_UNAVAILABLE)
            }
        }
    }

    /** 授权状态要问服务端，是同步 binder 调用，别在主线程做。 */
    private fun shizukuGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 管理器应用的中转 provider 解析得到 = Shizuku 装在这台机器上，问题在我们这边没握上手。 */
    private fun relayResolvable(): Boolean = runCatching {
        getApplication<Application>().packageManager
            .resolveContentProvider(SHIZUKU_RELAY_AUTHORITY, 0) != null
    }.getOrDefault(false)

    /**
     * 重启本应用——`SHIZUKU_NOT_CONNECTED` 只有这一种办法（新进程会重新握手）。
     *
     * 只在用户点按钮时做，不自动重启：应用自己悄悄消失再回来，比一句提示更让人不信任。
     * 先发起新的启动请求，隔一小会儿再退，免得把刚交出去的那次启动一起带走。
     */
    fun restartApp() {
        val context = getApplication<Application>()
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent == null) {
            RunLog.w("restart refused: no launch intent")
            return
        }
        RunLog.d("restart requested")
        runCatching {
            context.startActivity(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }.onFailure {
            RunLog.w("restart startActivity failed: ${it.message}")
            return
        }
        viewModelScope.launch {
            delay(RESTART_SETTLE_MS)
            exitProcess(0)
        }
    }

    /**
     * Shizuku 侧现状快照：日志头部要用它。
     *
     * relay 是管理器应用那个中转 provider。走 `bindUserService` 的年代它解析不到就是
     * 60 秒绑定超时；现在命令直接交给服务端进程，这个字段只用来回答「管理器应用还在不在」。
     */
    private fun shizukuSnapshot(): String = runCatching {
        val pm = getApplication<Application>().packageManager
        val relay = pm.resolveContentProvider(SHIZUKU_RELAY_AUTHORITY, 0) != null
        "running=${Shizuku.pingBinder()} granted=${shizukuGranted()} " +
            "serverVersion=${Shizuku.getVersion()} relay=$relay"
    }.getOrDefault("shizuku state unreadable")

    private var pollJob: Job? = null

    /**
     * 回到前台：判定一次、预热一次，并在「还在等用户到别的界面里做事」时定时复查。
     *
     * 预热是在服务端跑一条 `id`：第一次跨进程调用要拉起服务端的执行路径，别把这段开销
     * 留给用户的第一次拨开关。每个进程只做一次，代价几十毫秒。
     *
     * 用户很可能是在 Shizuku 自己的界面里点的授权，那种授予不会回调本应用，
     * 不复查就会一直停在「未授权」。只在服务端没起来 / 还没授权时轮询：
     * 判定虽然轻量，Root 模式和已经授权的状态也没必要每五秒问一次。
     */
    fun onForeground() {
        RunLog.d("onForeground")
        refreshPrivilegeStatus()
        if (!warmedUp) {
            warmedUp = true
            viewModelScope.launch(Dispatchers.IO) { warmUpExec() }
        }
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var attempts = 0
            while (attempts < POLL_MAX_ATTEMPTS) {
                val status = _privilegeStatus.value
                val waitingOnUser = status == PrivilegeStatus.SHIZUKU_NOT_RUNNING ||
                    status == PrivilegeStatus.SHIZUKU_PERMISSION_REQUIRED
                if (!waitingOnUser) break
                delay(POLL_INTERVAL_MS)
                attempts++
                refreshPrivilegeStatus()
            }
        }
    }

    /**
     * 预热：让服务端先跑一条 `id`。
     *
     * 与 [verifyNow] 的差别只在失败时怎么处理：预热失败不过是「用户那一下自己多等一会儿」，
     * 所以不动 status、也不清 [verified]——失败多半是这一刻服务端还没起来或被冻住，
     * 拿它当「链路坏了」的证据会把结论翻错；真坏了的时候操作路径自己会报。
     */
    private suspend fun warmUpExec() {
        // onForeground 比 DataStore 落地早几百毫秒，这时 tracked 还是初始的空集
        _trackedLoaded.first { it }
        if (privilegeMode.value != PrivilegeMode.SHIZUKU) return
        if (tracked.value.isEmpty()) return
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
        if (!shizukuGranted()) return
        if (freezeManager.probe()) markShizukuVerified() else RunLog.d("warm up did not confirm")
    }

    fun onBackground() {
        pollJob?.cancel()
        pollJob = null
    }

    /** 把「实测通过」固化下来：真的跑通了命令才算，操作成功与「验证」成功都走这里。 */
    private fun markShizukuVerified() {
        setVerified(true)
        setStatus(PrivilegeStatus.SHIZUKU_READY)
    }

    /**
     * 改写「实测通过」的结论。
     *
     * 调用方必须有正面证据：授权被撤（本地可查）、实测或操作确实失败。
     * 宿主进程被厂商省电策略冻结、特权进程被回收都不算证据——把它们当失败，
     * 结论会在 true/false 之间来回翻，而每翻成 false 一次，下次冷启动就多一次白绑。
     */
    private fun setVerified(verified: Boolean) {
        if (_verified.value == verified) return
        _verified.value = verified
        RunLog.d("verified -> $verified")
        viewModelScope.launch { settings.setShizukuVerified(verified) }
    }

    /** 以当前冻结状态重建主界面顺序快照。 */
    private fun rebuildHomeOrder() {
        homeOrder.value = _apps.value
            .filter { it.packageName in tracked.value }
            .sortedWith(HOME_ORDER)
            .map { it.packageName }
    }

    /**
     * 自动授权请求：一个 ViewModel 生命周期内只发一次。
     *
     * 对话框关闭会让本应用回到前台，若回到前台就重试，用户点完“允许”
     * 对话框会立刻再弹一次，看起来就像授权没生效。
     */
    fun requestShizukuPermissionOnce() {
        if (permissionRequested) return
        if (requestShizukuPermission()) permissionRequested = true
    }

    /**
     * 拉起 Shizuku 授权对话框。
     *
     * @return true 表示请求已发出（结果走 [permissionResultListener]）
     */
    fun requestShizukuPermission(): Boolean {
        // 状态是异步探测出来的，这里只能直接问现场：服务端在、且确实没授权才拉对话框
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) return false
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (granted) return false
        return runCatching {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            true
        }.getOrDefault(false)
    }

    /** 添加界面勾选 → 加入主界面跟踪列表。 */
    fun addTracked(packageName: String) {
        viewModelScope.launch { settings.addTracked(packageName) }
    }

    fun removeTracked(packageName: String) {
        viewModelScope.launch { settings.removeTracked(packageName) }
    }

    /**
     * 主界面长按：置顶 / 取消置顶。
     *
     * 这里必须留一行日志：进程被冻结时墙上时钟照走，解冻后长按层会把一次普通点击
     * 判成「按住超过阈值」——没有这行，界面上就是「点了没反应」，无从分辨。
     */
    fun togglePinned(packageName: String) {
        RunLog.d("long press -> pin $packageName")
        viewModelScope.launch { settings.togglePinned(packageName) }
    }

    fun setPrivilegeMode(mode: PrivilegeMode) {
        viewModelScope.launch {
            settings.setPrivilegeMode(mode)
            refreshPrivilegeStatus()
        }
    }

    /**
     * 切换界面语言：更新进程缓存并同步落 SharedPreferences。
     *
     * 用 SharedPreferences 而不是 DataStore：语言必须在第一个 Activity 的 attachBaseContext
     * 之前就定下来，而 DataStore 的读是异步的（真机首读 0.1~2.9 秒），赶不上。
     */
    fun setLanguageMode(mode: LanguageMode) {
        val context = getApplication<Application>()
        LocaleManager.mode = mode
        LocaleManager.save(context, mode)
        _languageMode.value = mode
    }

    /**
     * 只重新读取已跟踪应用的启用状态，并重建主界面顺序快照。
     * 主界面重新显示时调用：外部工具改动了冻结状态也能反映出来，
     * 界面上的开关操作攒到这一刻才重排，且不必重扫全部应用。
     */
    fun refreshTracked() {
        viewModelScope.launch {
            // 冷启动时缓存比这里慢半拍，先等列表落地再校正，否则等于什么都没刷
            _apps.first { it.isNotEmpty() }
            val trackedNow = tracked.value
            if (trackedNow.isEmpty()) return@launch
            val refreshed = appsRepo
                .refreshEnabled(_apps.value.filter { it.packageName in trackedNow })
                .associateBy { it.packageName }
            _apps.value = _apps.value.map { refreshed[it.packageName] ?: it }
            rebuildHomeOrder()
        }
    }

    /**
     * 切换某应用冻结状态。
     * @param entry 目标应用
     * @param enable true = 解冻（启用），false = 冻结（禁用）
     */
    fun toggle(entry: AppEntry, enable: Boolean) {
        val pkg = entry.packageName
        if (_busy.value == pkg) return
        RunLog.d("toggle $pkg -> ${if (enable) "unfreeze" else "freeze"} via ${privilegeMode.value}")
        viewModelScope.launch {
            _busy.value = pkg
            // 两条分支都要离开主线程：命令是同步 binder 事务 + 读管道，
            // 对方进程被冻住时事务会被推迟，主线程陪着卡帧
            val result = when (privilegeMode.value) {
                PrivilegeMode.SHIZUKU -> withContext(Dispatchers.IO) { shizukuToggle(pkg, enable) }
                PrivilegeMode.ROOT -> withContext(Dispatchers.IO) {
                    freezeManager.setEnabledRoot(pkg, enable)
                }
            }
            _lastResult.value = result
            if (result.success) {
                val index = _apps.value.indexOfFirst { it.packageName == pkg }
                if (index >= 0) {
                    val fresh = appsRepo.refreshEnabled(_apps.value[index])
                    _apps.value = _apps.value.toMutableList().also { it[index] = fresh }
                }
            }
            _busy.value = null
        }
    }

    private suspend fun shizukuToggle(pkg: String, enable: Boolean): FreezeResult {
        val context = getApplication<Application>()
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            setStatus(PrivilegeStatus.SHIZUKU_NOT_RUNNING)
            return FreezeResult(false, context.getString(R.string.shizuku_not_running))
        }
        // 操作本身就是最终判据：通了就固化「就绪」，没通就按本地事实重判一次显示，
        // 并把服务端给的原因原样抛回界面（未授权 / pm 拒绝 / binder 错误各不相同）
        val result = freezeManager.setEnabledShizuku(pkg, enable)
        if (result.success) {
            markShizukuVerified()
            return result
        }
        setVerified(false)
        judgePrivilege()
        return result
    }

    /**
     * 导出运行日志到应用自己的外部目录，界面上给出完整路径，取法：
     *
     *   adb pull /storage/emulated/0/Android/data/com.watchfridge.freezer/files/freezer-last.log
     */
    fun exportLog() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val snapshot = listOf(
                "privilege=${privilegeMode.value} status=${_privilegeStatus.value}",
                "verified=${_verified.value} verifiedRestored=$verifiedRestored",
                shizukuSnapshot(),
                "language=${languageMode.value}",
                "tracked=${tracked.value.sorted()}",
                "pinned=${pinned.value.sorted()}",
                "apps=${_apps.value.size} appsLoaded=${_appsLoaded.value}",
            )
            val file = withContext(Dispatchers.IO) { RunLog.export(context, snapshot) }
            _logExport.value = file?.absolutePath
                ?: context.getString(R.string.log_export_failed)
        }
    }

    fun clearLastResult() {
        _lastResult.value = null
    }

    private companion object {
        const val SHIZUKU_REQUEST_CODE = 1001

        /** Shizuku 管理器应用的中转 provider authority。 */
        const val SHIZUKU_RELAY_AUTHORITY = "moe.shizuku.privileged.api.shizuku"

        /** 授权回调后再确认一次的间隔，给服务端留出落库时间。 */
        const val GRANT_SETTLE_MS = 800L

        /** 发起重启到真正退出之间的间隔：给系统一点时间把新的启动请求落地。 */
        const val RESTART_SETTLE_MS = 400L

        /** 前台复查间隔与次数上限：约两分钟，够覆盖「去 Shizuku 里点完授权再回来」。 */
        const val POLL_INTERVAL_MS = 5_000L
        const val POLL_MAX_ATTEMPTS = 24

        /** 主界面快照的排序：未冻结在前，再按应用名。 */
        val HOME_ORDER: Comparator<AppEntry> =
            compareBy({ !it.enabled }, { it.label.lowercase() })

        /** 置顶区内只按应用名：冻结状态不参与，置顶的意义就是把它钉在最上面。 */
        val BY_LABEL: Comparator<AppEntry> = compareBy { it.label.lowercase() }

        /** 添加页默认顺序：第三方在前，同类按名字不区分大小写（与仓库里的全量扫描一致）。 */
        val BY_TYPE_THEN_NAME: Comparator<AppEntry> =
            compareBy<AppEntry>({ it.isSystem }).then { a, b ->
                String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label)
            }
    }
}
