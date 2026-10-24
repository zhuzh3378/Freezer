package com.watchfridge.freezer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.RadioButton
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip

/** 分组小标题。居中：圆屏上靠边的行弦长会变短，左对齐会被弧切掉。 */
@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = MaterialTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
    )
}

/**
 * 单选项。用 ToggleChip + RadioButton 控件，
 * 点击即选中（单选语义：选中项不可取消）。
 */
@Composable
fun RadioOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ToggleChip(
        checked = selected,
        onCheckedChange = { onClick() },
        label = {
            Text(
                text = label,
                fontSize = 13.sp,
                maxLines = 1,
            )
        },
        toggleControl = { RadioButton(selected = selected, colors = freezerRadioColors()) },
        colors = toggleRowTwoStateColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 开关项：改一个布尔设置（伪装模式这种），拨一下就生效。 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ToggleChip(
        checked = checked,
        onCheckedChange = onCheckedChange,
        label = {
            Text(
                text = label,
                fontSize = 13.sp,
                maxLines = 2,
            )
        },
        toggleControl = { Switch(checked = checked, colors = freezerSwitchColors()) },
        colors = toggleRowTwoStateColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 动作项：点击即执行，不保持状态（导出日志、打开链接这类一次性操作）。 */
@Composable
fun ActionRow(
    label: String,
    secondary: String? = null,
    onClick: () -> Unit,
) {
    Chip(
        label = {
            Column {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    maxLines = 2,
                )
                val sub = secondary
                if (sub != null) {
                    Text(
                        text = sub,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colors.onSurfaceVariant,
                    )
                }
            }
        },
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 状态说明行，必要时带一个动作按钮。 */
@Composable
fun StatusLine(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        val label = actionLabel
        val action = onAction
        if (label != null && action != null) {
            CompactChip(
                label = { Text(text = label, fontSize = 11.sp, maxLines = 1) },
                onClick = action,
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}
