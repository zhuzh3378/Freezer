package com.watchfridge.freezer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.ScalingParams
import androidx.wear.compose.material.ButtonColors
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.LocalContentColor
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.RadioButtonColors
import androidx.wear.compose.material.RadioButtonDefaults
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.SwitchColors
import androidx.wear.compose.material.SwitchDefaults
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipColors
import androidx.wear.compose.material.ToggleChipDefaults
import com.watchfridge.freezer.R
import com.watchfridge.freezer.core.DisplayShape
import com.watchfridge.freezer.data.AppEntry
import com.watchfridge.freezer.ui.theme.FridgePinnedPrimary
import com.watchfridge.freezer.ui.theme.FridgePinnedSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Wear Material 胶囊内图标的标准边长。 */
private const val ICON_SIZE_DP = 24

/** 图标缓存条数上限：预载是整表一次性装进来，留够余量。 */
private const val ICON_CACHE_MAX = 512

/**
 * 屏幕形状。由 MainActivity 从 ViewModel 提供：设置页改了形状，
 * 所有列表一起换缩放，不用每个界面各自去取一遍。
 */
val LocalDisplayShape = staticCompositionLocalOf { DisplayShape.ROUND }

/**
 * 圆表的鱼眼参数：保留缩放、去掉渐隐。
 *
 * 库里默认 edgeAlpha 只有 0.5，边缘每一项因此都要走一层离屏 alpha 合成，
 * 弱 GPU（模拟器、老款表）上滚动掉帧主要就来自这里，而渐隐在圆形屏上本来
 * 就看不太出来。
 */
private val fishEyeScalingParams: ScalingParams = ScalingLazyColumnDefaults.scalingParams(
    edgeAlpha = 1f,
)

/**
 * 方表的平铺参数：`edgeScale = 1f` 让边缘项不再缩小，纵向偏移也归零。
 *
 * 方屏的每一项本来就在矩形可视区内，鱼眼只会白白吃掉几行的高度；
 * 关掉缩放后滚动距离也更符合直觉。
 */
private val flatScalingParams: ScalingParams = ScalingLazyColumnDefaults.scalingParams(
    edgeScale = 1f,
    edgeAlpha = 1f,
    viewportVerticalOffsetResolver = { 0 },
)

/** 各界面共用的列表缩放参数：按当前形状在鱼眼与平铺之间取一。 */
@Composable
fun freezerScalingParams(): ScalingParams =
    if (LocalDisplayShape.current == DisplayShape.ROUND) fishEyeScalingParams
    else flatScalingParams

/**
 * 画在列表之上的时间，底下垫一条渐隐暗底。
 *
 * 调用位置很重要：要排在列表之后。Box 里后画的盖在前面之上，早先 TimeText 是
 * 列表的兄弟节点且排在最前，滚动上来的行直接糊在时间上。
 *
 * 暗底用一层渐变，而不是把 scalingParams 的 edgeAlpha 调回默认值：edgeAlpha 会给
 * 边缘每一项加一层离屏 alpha 合成，这台表上滚动掉帧主要来自它，所以宁可自己画一层。
 */
@Composable
fun BoxScope.TimeTextAboveList() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colors.background,
                    1f to MaterialTheme.colors.background.copy(alpha = 0f),
                )
            )
    )
    TimeText()
}

/**
 * 底部那两枚圆形按钮的样式：浅蓝填充 + 深蓝描边。
 *
 * 主界面与添加界面共用——同一个「往下伸手就有按钮」的动作，不该长两套。
 * 靠那圈深色描边和「已开启」的胶囊行区分开。
 */
@Composable
fun appFabStyle(): Pair<Modifier, ButtonColors> {
    val colors = MaterialTheme.colors
    return Modifier
        .size(ButtonDefaults.SmallButtonSize)
        .border(2.dp, colors.primaryVariant, CircleShape) to
        ButtonDefaults.buttonColors(
            backgroundColor = colors.primary,
            contentColor = colors.onPrimary,
        )
}

/**
 * 列表项共用的「应用名 + 包名」文字列。
 * 应用名加粗，包名同色系但更淡；两者都跟随胶囊的内容色，
 * 这样胶囊变成主题色时文字对比度仍然够。
 */
@Composable
fun AppLabelColumn(app: AppEntry, modifier: Modifier = Modifier) {
    val contentColor = LocalContentColor.current
    Column(modifier = modifier) {
        Text(
            text = app.label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = contentColor,
        )
        Text(
            text = app.packageName,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = contentColor.copy(alpha = 0.85f),
        )
    }
}

/**
 * 列表项共用的应用图标。
 *
 * 图标在应用列表扫完后由 [AppIconCache.preload] 一次性预载，进列表时基本都在
 * 缓存里命中，首帧就画真图标。这里不再按「是否在滚动」来决定加载与否：
 * 在懒加载项里读滚动状态，等于让每个可见行在每次甩动的起止各重组一次，
 * 而集中取图标又正好落在停手那一刻——比直接预载更抖。
 */
@Composable
fun AppIcon(app: AppEntry) {
    val context = LocalContext.current
    val sizePx = (ICON_SIZE_DP * context.resources.displayMetrics.density).toInt()
    var bitmap by remember(app.packageName) {
        mutableStateOf(AppIconCache.peek(app.packageName))
    }
    LaunchedEffect(app.packageName, sizePx) {
        if (bitmap == null) {
            bitmap = AppIconCache.load(context, app.packageName, sizePx)
        }
    }

    val size = Modifier.size(ICON_SIZE_DP.dp)
    val icon = bitmap
    if (icon != null) {
        Image(bitmap = icon, contentDescription = app.label, modifier = size)
    } else {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = app.label,
            modifier = size,
            colorFilter = ColorFilter.tint(MaterialTheme.colors.onSurfaceVariant),
        )
    }
}

/**
 * 主界面与添加界面共用的应用行。
 *
 * 用 ToggleChip 而不是裸 Row：整行都是点击区，右侧开关只是状态的可视化。
 * 开启时整条胶囊走主题色，关闭时回到常规灰色；置顶再压一档底色，
 * 让「钉在最上面」和「只是没冻结」在视觉上不至于混成一件事。
 *
 * @param colors 配色由界面层算一次传进来：长列表里每行都建一套对象会跟滚动抢主线程
 * @param onLongPress 置顶 / 取消置顶；不传就没有长按层（添加页只用来选跟踪项）
 */
@Composable
fun AppToggleRow(
    app: AppEntry,
    checked: Boolean,
    colors: AppRowColors,
    busy: Boolean = false,
    pinned: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    ToggleChip(
        checked = checked,
        onCheckedChange = onCheckedChange,
        label = { AppLabelColumn(app) },
        appIcon = { AppIcon(app) },
        // 处理中用转圈替换开关：胶囊颜色始终代表真实的冻结状态
        toggleControl = {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    indicatorColor = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
            } else {
                Switch(checked = checked, colors = colors.switch)
            }
        },
        colors = colors.chipFor(checked, pinned),
        modifier = Modifier
            .fillMaxWidth()
            .rowLongPress(onLongPress),
    )
}

/** 一行的配色：胶囊按「开启/关闭 × 置顶/普通」四态，开关只需两态。 */
class AppRowColors internal constructor(
    private val chipUnchecked: ToggleChipColors,
    private val chipChecked: ToggleChipColors,
    private val chipUncheckedPinned: ToggleChipColors,
    private val chipCheckedPinned: ToggleChipColors,
    val switch: SwitchColors,
) {
    fun chipFor(checked: Boolean, pinned: Boolean): ToggleChipColors = when {
        pinned && checked -> chipCheckedPinned
        pinned -> chipUncheckedPinned
        checked -> chipChecked
        else -> chipUnchecked
    }
}

@Composable
fun appRowColors(): AppRowColors {
    val colors = MaterialTheme.colors
    return AppRowColors(
        chipUnchecked = toggleRowColors(colors.surface, colors.onSurface, colors.onSurfaceVariant),
        chipChecked = toggleRowColors(colors.primary, colors.onPrimary, colors.onPrimary),
        chipUncheckedPinned = toggleRowColors(
            FridgePinnedSurface, colors.onSurface, colors.onSurfaceVariant
        ),
        chipCheckedPinned = toggleRowColors(FridgePinnedPrimary, colors.onPrimary, colors.onPrimary),
        switch = freezerSwitchColors(),
    )
}

/** 一套胶囊底色 + 对应的文字色：开关两侧同色，不走库自带的渐变。 */
@Composable
internal fun toggleRowColors(
    background: Color,
    contentColor: Color,
    secondaryContentColor: Color,
): ToggleChipColors = ToggleChipDefaults.toggleChipColors(
    checkedStartBackgroundColor = background,
    checkedEndBackgroundColor = background,
    checkedContentColor = contentColor,
    checkedSecondaryContentColor = secondaryContentColor,
    uncheckedStartBackgroundColor = background,
    uncheckedEndBackgroundColor = background,
    uncheckedContentColor = contentColor,
    uncheckedSecondaryContentColor = secondaryContentColor,
)

/** 设置页那种「选中才变色」的胶囊：一个对象同时描述两态，不需要置顶变体。 */
@Composable
internal fun toggleRowTwoStateColors(): ToggleChipColors {
    val colors = MaterialTheme.colors
    return ToggleChipDefaults.toggleChipColors(
        checkedStartBackgroundColor = colors.primary,
        checkedEndBackgroundColor = colors.primary,
        checkedContentColor = colors.onPrimary,
        checkedSecondaryContentColor = colors.onPrimary,
        uncheckedStartBackgroundColor = colors.surface,
        uncheckedEndBackgroundColor = colors.surface,
        uncheckedContentColor = colors.onSurface,
        uncheckedSecondaryContentColor = colors.onSurfaceVariant,
    )
}

/**
 * 行的长按层：只认长按，抬手点击仍然交给胶囊自己。
 *
 * 顺序很关键。父层在 Initial 段先于胶囊看到事件，按住过了阈值就把余下事件
 * 全部消费掉——胶囊在 Main 段收不到抬手，它的点击会取消，于是长按只会置顶，
 * 不会在置顶之后又顺手拨一次开关。反过来（等 Main 段再看）就来不及了。
 *
 * @param onLongPress 传 null 时整层不存在
 */
@Composable
fun Modifier.rowLongPress(onLongPress: (() -> Unit)?): Modifier {
    if (onLongPress == null) return this
    val viewConfig = LocalViewConfiguration.current
    val haptics = LocalHapticFeedback.current
    // 回调走 rememberUpdatedState：行一重组就换 lambda 实例，拿它当 key 会让
    // pointerInput 重启、把正按着的手指直接取消掉。
    val latestLongPress by rememberUpdatedState(onLongPress)
    return pointerInput(viewConfig) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val slopSquared = viewConfig.touchSlop * viewConfig.touchSlop
            // 返回 true = 阈值前就抬手或移走了，那是一次点击或滚动，不是长按
            val releasedEarly = withTimeoutOrNull(viewConfig.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) break
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (dx * dx + dy * dy > slopSquared) break
                }
                true
            }
            if (releasedEarly == true) return@awaitEachGesture
            latestLongPress?.invoke()
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}

/**
 * 开关配色。
 *
 * 库里默认用 secondary（绿）画拇指、再用三成透明画轨道，落在主题蓝胶囊上
 * 就是蓝底配一坨糊掉的绿。开启态跟着胶囊文字色走、关闭态跟着浅色文字走，
 * 两种底色上都能看清。轨道沿用默认的「拇指色 + 半透明」。
 */
@Composable
internal fun freezerSwitchColors(): SwitchColors {
    val colors = MaterialTheme.colors
    return SwitchDefaults.colors(
        checkedThumbColor = colors.onPrimary,
        uncheckedThumbColor = colors.onSurface,
        uncheckedTrackColor = colors.onSurfaceVariant.copy(alpha = 0.38f),
    )
}

/** 单选配色，规则与开关一致：选中看胶囊文字色，未选中看浅色描边。 */
@Composable
internal fun freezerRadioColors(): RadioButtonColors {
    val colors = MaterialTheme.colors
    return RadioButtonDefaults.colors(
        selectedRingColor = colors.onPrimary,
        selectedDotColor = colors.onPrimary,
        unselectedRingColor = colors.onSurfaceVariant,
        unselectedDotColor = colors.onSurfaceVariant,
    )
}

internal object AppIconCache {

    private val cache = LruCache<String, ImageBitmap>(ICON_CACHE_MAX)

    fun peek(packageName: String): ImageBitmap? = cache.get(packageName)

    /**
     * 整表预载图标：列表扫完后调一次，之后滚动过程中不再有取图标的开销。
     *
     * 顺序取而不并发：每次都要跨进程问 PackageManager，一起打过去会把
     * 系统侧排满，反而把前台的滚动请求也拖慢。
     */
    suspend fun preload(context: Context, packageNames: List<String>) = withContext(Dispatchers.IO) {
        val sizePx = (ICON_SIZE_DP * context.resources.displayMetrics.density).toInt()
        packageNames.forEach { load(context, it, sizePx) }
    }

    suspend fun load(context: Context, packageName: String, sizePx: Int): ImageBitmap? {
        peek(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val drawable = pm.getApplicationInfo(packageName, 0).loadIcon(pm)
                    ?: return@runCatching null
                val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(Canvas(bmp))
                bmp.asImageBitmap()
            }.getOrNull()?.also { cache.put(packageName, it) }
        }
    }
}
