package com.watchfridge.freezer.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.watchfridge.freezer.BuildConfig
import com.watchfridge.freezer.R

/** 仓库主页；「开源地址」按钮和它的二维码都用它。 */
private const val REPO_URL = "https://github.com/zhuzh3378/Freezer"

/** 开发者主页。 */
private const val PROFILE_URL = "https://github.com/zhuzh3378"

/** 地址在界面上去掉协议头：圆屏上少一次折行。 */
private const val REPO_HOST_PATH = "github.com/zhuzh3378/Freezer"
private const val PROFILE_HOST_PATH = "github.com/zhuzh3378"

/**
 * 关于页。结构照 Wraive 的关于页：
 * 应用身份 → 开发者 → 简介 → 开源项目 → 参考与致谢 → 版本记录 → 页脚。
 *
 * 一处不同：Wraive 的二维码是弹层（Material 3 的 `Dialog`），本项目在 Material 2，
 * dialog 包里只有固定布局的 `AlertDialog`，装不下二维码（边缘被切就扫不出了），
 * 所以二维码仍然是一整页。
 */
@Composable
fun AboutScreen(onOpenLink: (String) -> Unit) {
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
            // 与其它界面一致：自动居中会把首项滚到屏幕正中，顶部留白就白给了
            autoCentering = null,
            scalingParams = freezerScalingParams(),
            // 每一项都不做高：单项越高，落点越容易被顶到屏幕外。
            // 这块屏才 226dp 见方，顶部留白只要盖过时间文字，多了就是空白
            contentPadding = PaddingValues(top = 36.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "mark") { AppMark() }
            item(key = "identity") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colors.primaryVariant,
                    )
                    // 机型留在关于页是有用的：远程排障时第一眼就要知道是哪块表
                    Text(
                        text = Build.MODEL,
                        fontSize = 10.sp,
                        color = MaterialTheme.colors.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            item(key = "developer-label") {
                SectionTitle(stringResource(R.string.about_developer_section))
            }
            item(key = "developer") {
                AboutActionButton(
                    title = stringResource(R.string.about_author),
                    secondary = PROFILE_HOST_PATH,
                    avatar = true,
                    onClick = { onOpenLink(PROFILE_URL) },
                )
            }

            item(key = "intro-label") { SectionTitle(stringResource(R.string.about_intro_section)) }
            item(key = "intro") {
                AboutPanel(stringResource(R.string.app_name), Icons.Default.Info) {
                    Text(
                        text = stringResource(R.string.about_intro),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colors.onSurface,
                    )
                }
            }

            item(key = "source-label") {
                SectionTitle(stringResource(R.string.about_source_section))
            }
            item(key = "source") {
                AboutActionButton(
                    title = stringResource(R.string.about_source_action),
                    secondary = REPO_HOST_PATH,
                    onClick = { onOpenLink(REPO_URL) },
                )
            }

            item(key = "credits-label") {
                SectionTitle(stringResource(R.string.about_credits_section))
            }
            item(key = "credit-shizuku") {
                AboutPanel("Shizuku", Icons.Default.Settings) {
                    AboutBody(stringResource(R.string.about_credit_shizuku))
                }
            }
            item(key = "credit-zxing") {
                AboutPanel("zxing", Icons.Default.Star) {
                    AboutBody(stringResource(R.string.about_credit_zxing))
                }
            }

            item(key = "changelog-label") {
                SectionTitle(stringResource(R.string.about_changelog_section))
            }
            item(key = "changelog") {
                AboutPanel(stringResource(R.string.about_changelog_title), Icons.Default.Check) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.primary,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    ChangelogBullet(stringResource(R.string.about_change_1))
                    ChangelogBullet(stringResource(R.string.about_change_2))
                    ChangelogBullet(stringResource(R.string.about_change_3))
                    ChangelogBullet(stringResource(R.string.about_change_4))
                }
            }

            item(key = "footer") {
                Text(
                    text = stringResource(R.string.about_footer),
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/**
 * 应用图标：启动器那张是 adaptive icon（深蓝底 + 雪花前景），这里就照它画——
 * 底色的色值从 `@color/ic_launcher_background` 取，不在代码里再抄一遍十六进制。
 */
@Composable
private fun AppMark() {
    val context = LocalContext.current
    val background = Color(ContextCompat.getColor(context, R.color.ic_launcher_background))
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 大按钮：标题 + 一行地址。开发者与开源地址共用同一尺寸，
 * 免得同一页里出现两种「可以按」的分量。
 */
@Composable
private fun AboutActionButton(
    title: String,
    secondary: String,
    avatar: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = MaterialTheme.colors.surface,
            contentColor = MaterialTheme.colors.onSurface,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .border(1.5.dp, MaterialTheme.colors.primaryVariant, RoundedCornerShape(28.dp)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (avatar) {
                Image(
                    painter = painterResource(R.drawable.github_avatar),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape),
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = secondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** 带图标标题的圆角面板：简介、致谢、版本记录都用它。 */
@Composable
private fun AboutPanel(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colors.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.primary,
            )
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun AboutBody(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colors.onSurface,
    )
}

@Composable
private fun ChangelogBullet(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("• ", fontSize = 11.sp, color = MaterialTheme.colors.primary)
        Text(
            text = text,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colors.onSurface,
        )
    }
}
