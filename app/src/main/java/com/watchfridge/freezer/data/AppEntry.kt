package com.watchfridge.freezer.data

/**
 * 一个已安装应用的展示模型。图标由列表项按可见性异步加载，不随快照携带。
 *
 * @param launchable 这个包有没有能被启动器认出来的入口（MAIN/LAUNCHER activity）。
 *   按「结构」判断，不受我们自己冻结的影响：冻结过的包仍然算「可显示」，
 *   否则它就和「已启用/已禁用」那一组重复了。库里包、只提供 provider/服务 的包是 false。
 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    /** true = 已启用（解冻状态）；false = 已禁用（冻结状态）。 */
    val enabled: Boolean,
    val launchable: Boolean,
)
