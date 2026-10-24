package com.watchfridge.freezer.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.watchfridge.freezer.R

/**
 * 筛选界面。
 *
 * 添加界面的顶部空间要留给列表，所以两组条件挪到这里，选完右滑返回即生效
 * （条件存在 ViewModel 里，不需要确认按钮）。
 */
@Composable
fun FiltersScreen(viewModel: FreezerViewModel = viewModel()) {
    val filters by viewModel.filters.collectAsState()

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
            autoCentering = null,
            scalingParams = freezerScalingParams(),
            contentPadding = PaddingValues(top = 36.dp, bottom = 24.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.title_filters),
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { SectionTitle(stringResource(R.string.filter_section_type)) }
            item {
                RadioOption(
                    label = stringResource(R.string.filter_type_all),
                    selected = filters.type == TypeFilter.ALL,
                    onClick = { viewModel.setTypeFilter(TypeFilter.ALL) },
                )
            }
            item {
                RadioOption(
                    label = stringResource(R.string.filter_type_third_party),
                    selected = filters.type == TypeFilter.THIRD_PARTY,
                    onClick = { viewModel.setTypeFilter(TypeFilter.THIRD_PARTY) },
                )
            }
            item {
                RadioOption(
                    label = stringResource(R.string.filter_type_system),
                    selected = filters.type == TypeFilter.SYSTEM,
                    onClick = { viewModel.setTypeFilter(TypeFilter.SYSTEM) },
                )
            }

            item { SectionTitle(stringResource(R.string.filter_section_status)) }
            item {
                RadioOption(
                    label = stringResource(R.string.filter_status_all),
                    selected = filters.status == StatusFilter.ALL,
                    onClick = { viewModel.setStatusFilter(StatusFilter.ALL) },
                )
            }
            item {
                RadioOption(
                    label = stringResource(R.string.filter_status_enabled),
                    selected = filters.status == StatusFilter.ENABLED,
                    onClick = { viewModel.setStatusFilter(StatusFilter.ENABLED) },
                )
            }
            item {
                RadioOption(
                    label = stringResource(R.string.filter_status_disabled),
                    selected = filters.status == StatusFilter.DISABLED,
                    onClick = { viewModel.setStatusFilter(StatusFilter.DISABLED) },
                )
            }

            // 启动器里能不能看到这个入口：筛的是库里包、只挂服务或 provider 的包
            item { SectionTitle(stringResource(R.string.filter_section_launchable)) }
            item {
                RadioOption(
                    label = stringResource(R.string.filter_launchable_all),
                    selected = filters.launchable == LaunchableFilter.ALL,
                    onClick = { viewModel.setLaunchableFilter(LaunchableFilter.ALL) },
                )
            }
            item {
                RadioOption(
                    label = stringResource(R.string.filter_launchable_visible),
                    selected = filters.launchable == LaunchableFilter.VISIBLE,
                    onClick = { viewModel.setLaunchableFilter(LaunchableFilter.VISIBLE) },
                )
            }
            item {
                RadioOption(
                    label = stringResource(R.string.filter_launchable_hidden),
                    selected = filters.launchable == LaunchableFilter.HIDDEN,
                    onClick = { viewModel.setLaunchableFilter(LaunchableFilter.HIDDEN) },
                )
            }

            // 圆屏底部一行弦最短，按钮居中才不会碰到弧
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CompactChip(
                        label = {
                            Text(
                                text = stringResource(R.string.filter_reset),
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        },
                        onClick = { viewModel.resetFilters() },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
