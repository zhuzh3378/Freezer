package com.watchfridge.freezer.core

import android.content.Context

/** 屏幕形状：决定列表要不要鱼眼缩放。 */
enum class DisplayShape { ROUND, SQUARE }

/** 设置页选的档位。 */
enum class ShapeMode(val key: String) {
    /** 跟着系统报的圆屏标志走。 */
    AUTO("auto"),

    /** 强制按圆表排版（开鱼眼）。 */
    ROUND("round"),

    /** 强制按方表排版（关鱼眼）。 */
    SQUARE("square");

    companion object {
        fun fromKey(key: String?): ShapeMode =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * 形状判定与设置。
 *
 * 存 SharedPreferences 并同步读：鱼眼参数在第一次组合时就要定下来，
 * 走 DataStore（真机首读 0.1~2.9 秒）会先闪一版错的缩放。
 *
 * 判定只看 `Configuration.isScreenRound`：普通安卓手表（方屏、不声明 watch feature）
 * 报 false，于是落到 SQUARE —— 方屏本来就没有「边缘被弧切掉」的问题，鱼眼只会浪费可视高度。
 */
object DisplayShapeStore {

    private const val PREF_STORE = "display_shape"
    private const val PREF_KEY = "mode"

    fun mode(context: Context): ShapeMode = ShapeMode.fromKey(
        context.getSharedPreferences(PREF_STORE, Context.MODE_PRIVATE).getString(PREF_KEY, null)
    )

    fun save(context: Context, mode: ShapeMode) {
        context.getSharedPreferences(PREF_STORE, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY, mode.key)
            .apply()
    }

    @Suppress("DEPRECATION")
    fun detected(context: Context): DisplayShape =
        if (context.resources.configuration.isScreenRound) DisplayShape.ROUND
        else DisplayShape.SQUARE

    fun resolve(context: Context, mode: ShapeMode = mode(context)): DisplayShape = when (mode) {
        ShapeMode.AUTO -> detected(context)
        ShapeMode.ROUND -> DisplayShape.ROUND
        ShapeMode.SQUARE -> DisplayShape.SQUARE
    }

    /** 设置页那行说明用的分辨率，如 480×480。 */
    fun pixels(context: Context): String {
        val metrics = context.resources.displayMetrics
        return "${metrics.widthPixels}×${metrics.heightPixels}"
    }
}
