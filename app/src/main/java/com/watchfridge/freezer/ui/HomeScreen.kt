package com.watchfridge.freezer.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * 主界面：快捷冻结列表。
 *
 * - ScalingLazyColumn 鱼眼缩放，适配圆形屏幕，支持表冠滚动 + 吸附。
 * - 列表项：图标 → 加粗应用名 + 灰色包名 → 开关，整行可点。
 * - 底部固定：加号（添加应用）与齿轮（设置）。
 */
@Composable
fun HomeScreen(
    onAddApps: () -> Unit,
    onSettings: () -> Unit,
    viewModel: FreezerViewModel = viewModel(),
) {
    val apps by viewModel.trackedApps.collectAsState()
    val tracked by viewModel.tracked.collectAsState()
    val pinned by viewModel.pinned.collectAsState()
    val ready by viewModel.ready.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val lastResult by viewModel.lastResult.collectAsState()
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    // 整套配色算一次传给每一行，别在懒加载项里按行重建
    val rowColors = appRowColors()

    val doneText = stringResource(R.string.result_done)
    val failedText = stringResource(R.string.result_failed)

    LaunchedEffect(lastResult) {
        val result = lastResult ?: return@LaunchedEffect
        val text = if (result.success) {
            doneText
        } else {
            result.message.ifBlank { failedText }
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        viewModel.clearLastResult()
    }

    LaunchedEffect(Unit) { viewModel.refreshTracked() }

    Box(modifier = Modifier.fillMaxSize()) {
        // 有跟踪记录却还没拿到列表 = 还在加载；两者都不必请求列表焦点
        val showList = apps.isNotEmpty()
        if (showList) {
            ScalingLazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    // 逐格触感由这个 snap 行为发（hapticFeedbackEnabled 默认开），它挂在表冠
                    // 事件上，手指滑动不触发；自己再按滚动位置补一发就是两下叠在一起。
                    .rotaryScrollable(
                        RotaryScrollableDefaults.snapBehavior(scrollableState = listState),
                        focusRequester,
                    )
                    .focusRequester(focusRequester)
                    .focusable(),
                state = listState,
                // 自动居中会在首帧自己滚一次，把 contentPadding 的效果盖掉；关掉后由留白决定落点
                autoCentering = null,
                scalingParams = freezerScalingParams(),
                // 上下留白相等，任何一行都能被滚到屏幕中线（那里弦最长，胶囊不会被弧切掉）
                contentPadding = PaddingValues(top = 88.dp, bottom = 88.dp),
            ) {
                items(apps, key = { it.packageName }) { app ->
                    AppToggleRow(
                        app = app,
                        colors = rowColors,
                        // 开 = 已启用（未冻结），关 = 已冻结
                        checked = app.enabled,
                        busy = busy == app.packageName,
                        pinned = app.packageName in pinned,
                        // 长按只挪位置，不碰冻结状态：手抖的代价不该是误冻一个应用
                        onLongPress = { viewModel.togglePinned(app.packageName) },
                        onCheckedChange = { enable -> viewModel.toggle(app, enable) },
                    )
                }
            }

            // 滚动位置指示
            PositionIndicator(
                scalingLazyListState = listState,
                modifier = Modifier.align(Alignment.CenterEnd),
            )

            // 焦点只挂在列表上，因此只能在列表真正被组合时请求
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            val text = if (ready || tracked.isEmpty()) {
                stringResource(R.string.home_empty)
            } else {
                stringResource(R.string.list_loading)
            }
            EmptyPlaceholder(text)
        }

        // 时间画在列表之上：放在前面会被滚动上来的行糊住
        TimeTextAboveList()

        // 底部固定操作按钮：样式与添加界面共用（appFabStyle）
        val (buttonModifier, buttonColors) = appFabStyle()
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onAddApps,
                modifier = buttonModifier,
                colors = buttonColors,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.action_add),
                )
            }
            Button(
                onClick = onSettings,
                modifier = buttonModifier,
                colors = buttonColors,
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.title_settings),
                )
            }
        }
    }
}

/**
 * 主界面空态。
 *
 * 左右各留 30dp：圆形屏幕上过宽的一行字两端会被弧切掉。
 */
@Composable
private fun EmptyPlaceholder(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 30.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colors.onSurfaceVariant),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = text,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.onSurface,
        )
    }
}
