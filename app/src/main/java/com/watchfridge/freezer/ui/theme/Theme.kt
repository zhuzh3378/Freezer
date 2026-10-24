package com.watchfridge.freezer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Colors
import com.watchfridge.freezer.core.DisplayShape
import com.watchfridge.freezer.ui.LocalDisplayShape

/**
 * 深色底 + 单强调色。
 *
 * 背景纯黑（AMOLED 上省电，也让胶囊轮廓完全由 surface 决定），未开启的行压到
 * 近黑深灰；全应用只有冰蓝一个强调色，所以 secondary 不再另起一支绿——
 * 开关和胶囊一旦分属两套色相，圆屏上就只剩「花」了。
 */
private val FridgeColors = Colors(
    primary = Color(0xFF7CC4F0),
    primaryVariant = Color(0xFF2E5D80),
    secondary = Color(0xFF7CC4F0),
    secondaryVariant = Color(0xFF2A5573),
    error = Color(0xFFF2B8B5),
    onPrimary = Color(0xFF04263A),
    onSecondary = Color(0xFF04263A),
    onError = Color(0xFF410002),
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFB9C0C7),
    surface = Color(0xFF16191C),
    background = Color.Black,
)

/** 置顶 + 未开启：比 surface 多一档蓝，和「没开启」区分开。 */
internal val FridgePinnedSurface = Color(0xFF1F2B35)

/** 置顶 + 已开启：主色压暗一档，和「只是没冻结」区分开。 */
internal val FridgePinnedPrimary = Color(0xFF5A9CC6)

@Composable
fun FridgeTheme(
    /** 当前屏幕形状：主题层提供一次，各界面的列表缩放都跟着它走。 */
    shape: DisplayShape,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalDisplayShape provides shape,
        content = { MaterialTheme(colors = FridgeColors, content = content) },
    )
}
