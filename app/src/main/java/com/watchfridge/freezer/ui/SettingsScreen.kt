package com.watchfridge.freezer.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.watchfridge.freezer.R
import com.watchfridge.freezer.core.DisplayShape
import com.watchfridge.freezer.core.DisplayShapeStore
import com.watchfridge.freezer.core.LanguageMode
import com.watchfridge.freezer.core.ShapeMode
import com.watchfridge.freezer.data.PrivilegeMode

/**
 * 设置界面。
 *
 * - 权限来源：Shizuku / Root 单选切换。
 * - 应用语言：自动 / 中文 / English，切换后更新进程语言并重建界面。
 * - 诊断：把本进程的运行日志写成文件，供电脑上 adb pull 取回。
 */
@Composable
fun SettingsScreen(
    onAbout: () -> Unit,
    viewModel: FreezerViewModel = viewModel(),
) {
    val context = LocalContext.current

    val privilege by viewModel.privilegeMode.collectAsState()
    val language by viewModel.languageMode.collectAsState()
    val masquerade by viewModel.masquerade.collectAsState()
    val shapeMode by viewModel.shapeMode.collectAsState()

    // 语言先同步写进 SharedPreferences（冷启动要靠它），再重建界面让新的 Context 生效
    val pickLanguage: (LanguageMode) -> Unit = { mode ->
        viewModel.setLanguageMode(mode)
        (context as? ComponentActivity)?.recreate()
    }

    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .rotaryScrollable(
                    RotaryScrollableDefaults.snapBehavior(scrollableState = listState),
                    focusRequester,
                )
                .focusRequester(focusRequester)
                .focusable(),
            state = listState,
            // 与主界面一致：自动居中会把标题滚到屏幕中间，下面的选项全被挤到圆弧外
            autoCentering = null,
            scalingParams = freezerScalingParams(),
            contentPadding = PaddingValues(top = 36.dp, bottom = 24.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.title_settings),
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 权限来源
            item { SectionTitle(stringResource(R.string.setting_privilege)) }
            item {
                RadioOption(
                    label = stringResource(R.string.privilege_shizuku),
                    selected = privilege == PrivilegeMode.SHIZUKU,
                    onClick = { viewModel.setPrivilegeMode(PrivilegeMode.SHIZUKU) },
                )
            }
            item {
                RadioOption(
                    label = stringResource(R.string.privilege_root),
                    selected = privilege == PrivilegeMode.ROOT,
                    onClick = { viewModel.setPrivilegeMode(PrivilegeMode.ROOT) },
                )
            }
            item { PrivilegeStatusLine(viewModel) }

            // 应用语言
            item { SectionTitle(stringResource(R.string.setting_language)) }
            item {
                RadioOption(
                    label = stringResource(R.string.language_auto),
                    selected = language == LanguageMode.AUTO,
                    onClick = { pickLanguage(LanguageMode.AUTO) },
                )
            }
            item {
                RadioOption(
                    label = stringResource(R.string.language_zh),
                    selected = language == LanguageMode.ZH,
                    onClick = { pickLanguage(LanguageMode.ZH) },
                )
            }
            item {
                RadioOption(
                    label = stringResource(R.string.language_en),
                    selected = language == LanguageMode.EN,
                    onClick = { pickLanguage(LanguageMode.EN) },
                )
            }

            // 屏幕形状：圆表鱼眼、方表平铺；默认跟着系统报的形状
            item { SectionTitle(stringResource(R.string.setting_shape)) }
            item {
                RadioOption(
                    label = stringResource(R.string.shape_auto),
                    selected = shapeMode == ShapeMode.AUTO,
                    onClick = { viewModel.setShapeMode(ShapeMode.AUTO) },
                )
            }
            item {
                RadioOption(
                    label = stringResource(R.string.shape_round),
                    selected = shapeMode == ShapeMode.ROUND,
                    onClick = { viewModel.setShapeMode(ShapeMode.ROUND) },
                )
            }
            item {
                RadioOption(
                    label = stringResource(R.string.shape_square),
                    selected = shapeMode == ShapeMode.SQUARE,
                    onClick = { viewModel.setShapeMode(ShapeMode.SQUARE) },
                )
            }
            item {
                val detected = if (DisplayShapeStore.detected(context) == DisplayShape.ROUND) {
                    stringResource(R.string.shape_detected_round)
                } else {
                    stringResource(R.string.shape_detected_square)
                }
                StatusLine(
                    stringResource(
                        R.string.shape_status,
                        DisplayShapeStore.pixels(context),
                        detected,
                    ),
                )
            }

            // 伪装模式：换掉启动器入口的图标与名称，进来先看到一块假门扉页
            item { SectionTitle(stringResource(R.string.setting_masquerade)) }
            item {
                SwitchRow(
                    label = stringResource(R.string.masquerade_switch),
                    checked = masquerade,
                    onCheckedChange = { enabled ->
                        viewModel.setMasquerade(enabled)
                        // 门扉页是在 onCreate 定下来的，重建一次界面才看得到新形态
                        (context as? ComponentActivity)?.recreate()
                    },
                )
            }
            item { StatusLine(stringResource(R.string.masquerade_hint)) }

            // 关于：独立一页（头像、简介、仓库地址、二维码 / 在手机上打开）
            item { SectionTitle(stringResource(R.string.setting_about)) }
            item {
                ActionRow(
                    label = stringResource(R.string.about_open_repo),
                    onClick = onAbout,
                )
            }

            // 诊断：出问题时把这一进程的运行日志落到文件里，回电脑上取
            item { SectionTitle(stringResource(R.string.setting_diagnostics)) }
            item {
                ActionRow(
                    label = stringResource(R.string.export_log),
                    onClick = { viewModel.exportLog() },
                )
            }
            item {
                val exported by viewModel.logExport.collectAsState()
                StatusLine(
                    text = exported ?: stringResource(R.string.export_log_hint),
                )
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/**
 * 权限来源当前可用性。
 *
 * 每次进设置页只做轻量判定（服务端在不在、授权有没有、上次实测过没有），
 * 不在这里顺手绑服务：一次绑定要 6~28 秒，那是用户点「验证」或拨开关时才该付的开销。
 */
@Composable
private fun PrivilegeStatusLine(viewModel: FreezerViewModel) {
    LaunchedEffect(Unit) { viewModel.refreshPrivilegeStatus() }
    val status by viewModel.privilegeStatus.collectAsState()

    val needsAuthorize = status == PrivilegeStatus.SHIZUKU_PERMISSION_REQUIRED
    val needsVerify = status == PrivilegeStatus.SHIZUKU_UNVERIFIED ||
        status == PrivilegeStatus.SHIZUKU_UNAVAILABLE
    val needsRestart = status == PrivilegeStatus.SHIZUKU_NOT_CONNECTED

    val text = when (status) {
        PrivilegeStatus.SHIZUKU_READY -> stringResource(R.string.priv_status_shizuku_ready)
        PrivilegeStatus.SHIZUKU_UNVERIFIED ->
            stringResource(R.string.priv_status_shizuku_unverified)

        PrivilegeStatus.SHIZUKU_PROBING -> stringResource(R.string.priv_status_shizuku_probing)
        PrivilegeStatus.SHIZUKU_NOT_RUNNING ->
            stringResource(R.string.priv_status_shizuku_not_running)

        PrivilegeStatus.SHIZUKU_NOT_CONNECTED ->
            stringResource(R.string.priv_status_shizuku_not_connected)

        PrivilegeStatus.SHIZUKU_PERMISSION_REQUIRED ->
            stringResource(R.string.priv_status_shizuku_permission)

        PrivilegeStatus.SHIZUKU_UNAVAILABLE ->
            stringResource(R.string.priv_status_shizuku_unavailable)

        PrivilegeStatus.ROOT -> stringResource(R.string.priv_status_root)
    }

    val authorize: () -> Unit = { viewModel.requestShizukuPermission() }
    val verify: () -> Unit = { viewModel.verifyNow() }
    val restart: () -> Unit = { viewModel.restartApp() }

    StatusLine(
        text = text,
        actionLabel = when {
            needsAuthorize -> stringResource(R.string.priv_action_authorize)
            needsVerify -> stringResource(R.string.priv_action_verify)
            needsRestart -> stringResource(R.string.priv_action_restart)
            else -> null
        },
        onAction = when {
            needsAuthorize -> authorize
            needsVerify -> verify
            needsRestart -> restart
            else -> null
        },
    )
}
