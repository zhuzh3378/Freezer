package com.watchfridge.freezer.ui

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Text
import com.watchfridge.freezer.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 应用类型筛选。 */
enum class TypeFilter { ALL, THIRD_PARTY, SYSTEM }

/** 启用状态筛选。 */
enum class StatusFilter { ALL, ENABLED, DISABLED }

/**
 * 启动器可见性筛选：这个包有没有能在主桌面/启动器里露出来的入口。
 *
 * 按结构判断，不看我们有没有冻它——冻掉的包照样算「可显示」，
 * 这一组筛出来的是库里包、只挂 provider 或服务的那种。
 */
enum class LaunchableFilter { ALL, VISIBLE, HIDDEN }

/** 敲字后等多久才重排列表。 */
private const val FILTER_DEBOUNCE_MS = 220L

/**
 * 添加界面。
 *
 * - 顶部只留时间，应用列表从第一条就开始；搜索与筛选收进底部两枚圆形图标，
 *   和主界面的 +/齿轮 是同一套交互。
 * - 点搜索进入输入态：输入框换到顶部（键盘从下面升起来不会盖住它），
 *   底部按钮让位给列表。
 * - 筛选条件存在 ViewModel 里，去筛选页改完滑回来即生效。
 *
 * 输入框固定在列表外面：列表会随输入不断重排，输入框若也在 LazyColumn 里
 * 就会被回收重建，输入法连接随之断开，导致打字内容不回显。
 */
@Composable
fun AddAppsScreen(
    onFilters: () -> Unit,
    viewModel: FreezerViewModel = viewModel(),
) {
    val visibleApps by viewModel.visibleApps.collectAsState()
    val tracked by viewModel.tracked.collectAsState()
    val ready by viewModel.ready.collectAsState()
    val filters by viewModel.filters.collectAsState()

    var searching by remember { mutableStateOf(false) }

    val listState = rememberScalingLazyListState()
    val listFocus = remember { FocusRequester() }
    // 整套配色算一次传给每一行，别在懒加载项里按行重建
    val rowColors = appRowColors()

    // 进入本页定一次顺序：之后勾选/取消不再重排，切走再回来或改筛选条件才重排
    LaunchedEffect(Unit) { viewModel.rebuildAddOrder() }

    // 筛选条件不再显示在顶部，列表被筛窄后先在首行说明是按什么筛的
    val filterSummary = listOfNotNull(
        filters.query.takeIf { it.isNotBlank() },
        when (filters.type) {
            TypeFilter.ALL -> null
            TypeFilter.THIRD_PARTY ->
                stringResource(R.string.filter_type_third_party)

            TypeFilter.SYSTEM -> stringResource(R.string.filter_type_system)
        },
        when (filters.status) {
            StatusFilter.ALL -> null
            StatusFilter.ENABLED -> stringResource(R.string.filter_status_enabled)
            StatusFilter.DISABLED -> stringResource(R.string.filter_status_disabled)
        },
        when (filters.launchable) {
            LaunchableFilter.ALL -> null
            LaunchableFilter.VISIBLE -> stringResource(R.string.filter_launchable_visible)
            LaunchableFilter.HIDDEN -> stringResource(R.string.filter_launchable_hidden)
        },
    ).joinToString(" · ")

    Box(modifier = Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .rotaryScrollable(
                    RotaryScrollableDefaults.snapBehavior(scrollableState = listState),
                    listFocus,
                )
                .focusRequester(listFocus)
                .focusable(),
            state = listState,
            // 关掉自动居中：否则首行会落到列表区中间，顶部留白也白给了
            autoCentering = null,
            scalingParams = freezerScalingParams(),
            // 底部要盖住圆形按钮那一圈，顶部要避开时间文字
            contentPadding = PaddingValues(
                top = if (searching) 80.dp else 48.dp,
                bottom = if (searching) 18.dp else 70.dp,
            ),
        ) {
            if (filterSummary.isNotEmpty()) {
                item(key = "filter_summary") {
                    Text(
                        text = filterSummary,
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 2.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            items(visibleApps, key = { it.packageName }) { app ->
                val checked = app.packageName in tracked
                AppToggleRow(
                    app = app,
                    colors = rowColors,
                    checked = checked,
                    onCheckedChange = { isChecked ->
                        if (isChecked) viewModel.addTracked(app.packageName)
                        else viewModel.removeTracked(app.packageName)
                    },
                )
            }
        }

        // 时间画在列表之上；输入态把顶部让给搜索框
        if (!searching) TimeTextAboveList()

        if (searching) {
            SearchInput(
                initialQuery = filters.query,
                onQueryChange = { viewModel.setQuery(it) },
                onClose = {
                    searching = false
                    viewModel.setQuery("")
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 30.dp),
            )
        } else {
            // 底部固定：搜索 + 筛选（三横线）。样式与主界面那两枚完全一样；
            // 「筛选是否生效」不再靠按钮变色表达，列表首行的筛选摘要就是信号
            val (fabModifier, fabColors) = appFabStyle()
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { searching = true },
                    modifier = fabModifier,
                    colors = fabColors,
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.action_search),
                    )
                }
                Button(
                    onClick = onFilters,
                    modifier = fabModifier,
                    colors = fabColors,
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = stringResource(R.string.action_filters),
                    )
                }
            }
        }

        PositionIndicator(
            scalingLazyListState = listState,
            modifier = Modifier.align(Alignment.CenterEnd),
        )

        if (!ready && visibleApps.isEmpty()) {
            Text(
                text = stringResource(R.string.list_loading),
                fontSize = 13.sp,
                color = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 30.dp),
            )
        }
    }

    // 非输入态才把焦点还给列表：输入态焦点属于搜索框
    LaunchedEffect(searching) {
        if (!searching) runCatching { listFocus.requestFocus() }
    }
}

/**
 * 搜索输入态：原生 EditText + 退出按钮。
 *
 * 换成原生控件的原因：Compose 自己实现的那套 `InputConnection` 与第三方输入法（Gboard）
 * 不同步——已经打好的字不实时回显、要等输入完成才出现，退格也删不干净。
 * 原生 `EditText` 的 InputConnection 由框架实现，是已被验证可用的那条路
 * （Wraive 的《手表输入框修复说明》同样结论，它也是这么改的）。
 *
 * 两个必须守住的细节，漏掉任何一个就等于白改：
 * - `AndroidView` 的 update 每次按键都会跑。颜色、字号无条件往 EditText 上写，
 *   框架会 `restartInput()`，恰好把刚修好的合成态冲掉——所以全部收进「配置指纹」，
 *   指纹变了才应用。
 * - 返回键分两段：第一次只关键盘并交出焦点，第二次才退界面。
 *
 * 文本仍隔一拍才推给 ViewModel：一边敲一边重排整张列表会让输入法反复重建抽取上下文。
 */
@Composable
private fun SearchInput(
    initialQuery: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnChange by rememberUpdatedState(onQueryChange)
    val config = NativeSearchConfig(
        textColor = MaterialTheme.colors.onSurface.toArgb(),
        hintColor = MaterialTheme.colors.onSurfaceVariant.toArgb(),
        textSizeSp = 15f,
    )
    var field by remember { mutableStateOf<SearchEditText?>(null) }
    var debounce: Job? by remember { mutableStateOf(null) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AndroidView(
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colors.surface),
            factory = { ctx ->
                SearchEditText(ctx).apply {
                    // 背景与内边距由外层 Compose 管，这里只留文字与输入行为
                    background = null
                    setSingleLine()
                    inputType = InputType.TYPE_CLASS_TEXT
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    hint = ctx.getString(R.string.search_hint)
                    setPadding(
                        (12 * resources.displayMetrics.density).toInt(), 0,
                        (12 * resources.displayMetrics.density).toInt(), 0,
                    )
                    setText(initialQuery)
                    setSelection(text.length)
                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            hideKeyboard(context)
                            clearFocus()
                            true
                        } else {
                            false
                        }
                    }
                    doAfterTextChanged { text ->
                        debounce?.cancel()
                        val value = text.toString()
                        debounce = scope.launch {
                            delay(FILTER_DEBOUNCE_MS)
                            latestOnChange(value)
                        }
                    }
                    field = this
                }
            },
            update = { view ->
                if (view.tag != config) {
                    view.applySearchStyle(config)
                    view.tag = config
                }
            },
        )

        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(percent = 50))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.action_close_search),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colors.onSurfaceVariant,
            )
        }
    }

    LaunchedEffect(Unit) {
        field?.let { it.requestFocus(); it.showKeyboard(context) }
    }
    // 退出输入态时收回键盘：这行不做的话键盘会留在上一页上
    DisposableEffect(Unit) {
        onDispose { field?.hideKeyboard(context) }
    }
}

/** 原生输入框的配置指纹：只有它变了才往 EditText 上写。 */
private data class NativeSearchConfig(
    val textColor: Int,
    val hintColor: Int,
    val textSizeSp: Float,
)

private fun EditText.applySearchStyle(config: NativeSearchConfig) {
    setTextColor(config.textColor)
    setHintTextColor(config.hintColor)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, config.textSizeSp)
}

/**
 * 搜索用的原生输入框。
 *
 * 只覆盖一处行为：返回键。第一次返回关掉键盘并交出焦点，界面留在本页；
 * 第二次才走系统那条 pop 的路。不做这个的话，打完字按返回会把整张添加页一起退掉。
 */
private class SearchEditText(context: Context) : EditText(context) {

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            hideKeyboard(context)
            clearFocus()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}

private fun EditText.showKeyboard(context: Context) {
    val imm = context.getSystemService(InputMethodManager::class.java) ?: return
    imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
}

private fun EditText.hideKeyboard(context: Context) {
    val token = windowToken ?: return
    context.getSystemService(InputMethodManager::class.java)
        ?.hideSoftInputFromWindow(token, 0)
}
