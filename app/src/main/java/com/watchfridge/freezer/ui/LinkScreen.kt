package com.watchfridge.freezer.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.watchfridge.freezer.R
import com.watchfridge.freezer.core.RunLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 二维码边长（dp）。这块屏是 480px / 密度 340 ≈ 226dp 见方，
 * 96dp = 204px，约占屏宽 43%：这个长度的 URL 是 25 个模块，每模块 8px，
 * 手机凑近能扫；再大就要顶到圆的弦上，也挤不掉上方那块时间文字。
 */
private const val QR_SIZE_DP = 96

/**
 * 链接页：上面二维码、中间地址、下面「在手机上打开」。
 *
 * 码在本地生成（zxing 的 `QRCodeWriter`），不联网、不需要 INTERNET 权限。
 * 编码放后台线程：120dp 按两倍像素出图是 240×240 ≈ 五万八千个像素，
 * 摆主线程上就是一帧以上的卡顿。
 *
 * 布局走和其它界面一样的 `ScalingLazyColumn`，不是 `Box(center)` 硬居中：
 * 居中块不管顶部时间，`TimeText` 会压在码上；而整宽的按钮落在中心线以下，
 * 那里圆的弦本来就短，两端会被弧切掉。鱼眼列表会自己把偏离中心的项缩小，
 * 再加一层横向留白才贴得住圆屏。
 */
@Composable
fun LinkScreen(url: String) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .rotaryScrollable(
                    RotaryScrollableDefaults.snapBehavior(scrollableState = listState),
                    focusRequester,
                )
                .focusRequester(focusRequester)
                .focusable(),
            state = listState,
            autoCentering = null,
            scalingParams = freezerScalingParams(),
            // 顶部只要盖过时间文字即可：整页内容约 160dp，屏才 226dp，
            // 留白一大就把按钮挤到下面的短弦上（上一版给到 92dp，上方空掉四成屏高）
            contentPadding = PaddingValues(top = 32.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "qr") {
                val shown = bitmap
                if (shown == null) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                } else {
                    Image(
                        bitmap = shown,
                        contentDescription = stringResource(R.string.qr_description),
                        modifier = Modifier
                            .size(QR_SIZE_DP.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White),
                    )
                }
            }
            item(key = "url") {
                Text(
                    text = url,
                    fontSize = 10.sp,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 26.dp),
                )
            }
            item(key = "open") {
                val okText = stringResource(R.string.about_phone_ok)
                val failText = stringResource(R.string.about_phone_fail)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 30.dp),
                ) {
                    ActionRow(
                        label = stringResource(R.string.about_open_on_phone),
                        onClick = { openUrlOnPhone(context, url, okText, failText) },
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(density, url) {
        // 按两倍的像素生成再缩着画：模块边界落在像素上，扫起来不费劲
        val px = (with(density) { QR_SIZE_DP.dp.toPx() } * 2).toInt().coerceAtLeast(192)
        bitmap = withContext(Dispatchers.Default) { encodeQr(url, px) }
    }
}

private fun encodeQr(text: String, size: Int): ImageBitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 1),
    )
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) {
            pixels[row + x] = if (matrix.get(x, y)) BLACK else WHITE
        }
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrElse {
    // 地址是常量，编不出来只可能是库或参数问题；留一行日志，界面上停在转圈
    RunLog.w("qr encode failed: ${it.javaClass.simpleName} ${it.message}")
    null
}

private val BLACK = android.graphics.Color.BLACK
private val WHITE = android.graphics.Color.WHITE

/**
 * 把打开链接这件事交给配对的手机。
 *
 * `RemoteActivityHelper` 会把 Intent 包进 `ACTION_REMOTE_INTENT` 发给手机上的 Wearable
 * 服务，由手机挑一个浏览器执行。手表上没装浏览器时，直接 `startActivity` 只会抛
 * `ActivityNotFoundException`——所以「在手机上打开」必须走这条。
 *
 * 结果由 future 给，不猜：`get()` 抛异常就是手机那边没接住（没连上、或没有能接
 * ACTION_VIEW 的应用），原因进日志，界面上只留一句人话。
 */
private fun openUrlOnPhone(context: Context, url: String, okText: String, failText: String) {
    val executor = ContextCompat.getMainExecutor(context)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val helper = RemoteActivityHelper(context, executor)
    val future = helper.startRemoteActivity(intent)
    future.addListener({
        val message = runCatching { future.get(); okText }.getOrElse {
            RunLog.w("open $url on phone failed: ${it.message}")
            failText
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }, executor)
}
