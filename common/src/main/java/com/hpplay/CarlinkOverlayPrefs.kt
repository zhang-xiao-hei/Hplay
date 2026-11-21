package com.hpplay

import android.content.Context

/**
 * CarLink 投屏界面上两个悬浮按钮（「退出」/「设置」）的显示与像素级位置。
 *
 * 参考 APK（Wcast 互联 / ICCOA）在连接成功后会在投屏画面上叠一层最小控件：左上「退出」、
 * 右上「设置」。不同车机的屏幕尺寸、状态栏高度、内饰遮挡都不一样，所以这里把「显示不显示」
 * 和「落在哪个像素」都做成用户可调项，而不是写死 dp。
 *
 * 约定：
 *  - [AUTO]（-1）表示用宿主内置的默认位置（退出在左上、设置在右上第二行）；
 *  - 其余值就是**坐标基准分辨率下的绝对像素**（基准是 [Overlay.basisWidth]/[Overlay.basisHeight]，
 *    未设置时等于宿主当前窗口尺寸）。基准与当前画面尺寸不一致时，宿主按比例换算后再落位，
 *    这样 1280×720 的存档换到 1920×1080 的机子上不会跑到屏幕外。
 */
object CarlinkOverlayPrefs {

    private const val FILE = "carlink_overlay"
    private const val KEY_SHOW_EXIT = "show_exit"
    private const val KEY_SHOW_SETTINGS = "show_settings"
    private const val KEY_EXIT_X = "exit_x"
    private const val KEY_EXIT_Y = "exit_y"
    private const val KEY_SETTINGS_X = "settings_x"
    private const val KEY_SETTINGS_Y = "settings_y"
    private const val KEY_BASIS_W = "basis_w"
    private const val KEY_BASIS_H = "basis_h"
    private const val KEY_STREAM_W = "stream_w"
    private const val KEY_STREAM_H = "stream_h"

    /** 位置未设置：跟随宿主默认布局。 */
    const val AUTO = -1

    data class Overlay(
        val showExit: Boolean,
        val showSettings: Boolean,
        val exitX: Int,
        val exitY: Int,
        val settingsX: Int,
        val settingsY: Int,
        /** 坐标基准分辨率；0 表示未设置，按宿主当前窗口尺寸解释。 */
        val basisWidth: Int = 0,
        val basisHeight: Int = 0,
        /** 上一次投屏实际使用的编码分辨率（手机侧），仅作参考 / 一键选用。 */
        val streamWidth: Int = 0,
        val streamHeight: Int = 0,
    ) {
        /** 退出按钮是否给了绝对坐标。 */
        val exitHasPosition: Boolean get() = exitX != AUTO || exitY != AUTO

        /** 设置按钮是否给了绝对坐标。 */
        val settingsHasPosition: Boolean get() = settingsX != AUTO || settingsY != AUTO

        /** 坐标基准：存过就用存的，没存过就等于当前画面尺寸（兼容老存档）。 */
        fun basis(panelWidth: Int, panelHeight: Int): Pair<Int, Int> =
            if (basisWidth > 0 && basisHeight > 0) {
                basisWidth to basisHeight
            } else {
                panelWidth.coerceAtLeast(1) to panelHeight.coerceAtLeast(1)
            }
    }

    fun load(context: Context): Overlay {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Overlay(
            showExit = prefs.getBoolean(KEY_SHOW_EXIT, true),
            showSettings = prefs.getBoolean(KEY_SHOW_SETTINGS, true),
            exitX = prefs.getInt(KEY_EXIT_X, AUTO),
            exitY = prefs.getInt(KEY_EXIT_Y, AUTO),
            settingsX = prefs.getInt(KEY_SETTINGS_X, AUTO),
            settingsY = prefs.getInt(KEY_SETTINGS_Y, AUTO),
            basisWidth = prefs.getInt(KEY_BASIS_W, 0),
            basisHeight = prefs.getInt(KEY_BASIS_H, 0),
            streamWidth = prefs.getInt(KEY_STREAM_W, 0),
            streamHeight = prefs.getInt(KEY_STREAM_H, 0),
        )
    }

    fun save(context: Context, overlay: Overlay) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SHOW_EXIT, overlay.showExit)
            .putBoolean(KEY_SHOW_SETTINGS, overlay.showSettings)
            .putInt(KEY_EXIT_X, overlay.exitX)
            .putInt(KEY_EXIT_Y, overlay.exitY)
            .putInt(KEY_SETTINGS_X, overlay.settingsX)
            .putInt(KEY_SETTINGS_Y, overlay.settingsY)
            .putInt(KEY_BASIS_W, overlay.basisWidth)
            .putInt(KEY_BASIS_H, overlay.basisHeight)
            .putInt(KEY_STREAM_W, overlay.streamWidth)
            .putInt(KEY_STREAM_H, overlay.streamHeight)
            .apply()
    }

    fun saveExitPosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_EXIT_X, x)
            .putInt(KEY_EXIT_Y, y)
            .apply()
    }

    fun saveSettingsPosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SETTINGS_X, x)
            .putInt(KEY_SETTINGS_Y, y)
            .apply()
    }

    /**
     * 记下本次投屏真实的编码分辨率（手机侧）。设置页据此给出「用上次投屏分辨率」一键基准；
     * 只写分辨率，不动显隐和坐标，所以可以在每次视频就绪时随手调用。
     */
    fun saveStreamResolution(context: Context, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_STREAM_W, width)
            .putInt(KEY_STREAM_H, height)
            .apply()
    }

    /** 恢复默认：两个按钮都显示，位置回落到宿主默认布局，坐标基准清掉（按当前画面解释）。 */
    fun reset(context: Context) {
        val current = load(context)
        save(
            context,
            Overlay(
                showExit = true,
                showSettings = true,
                exitX = AUTO,
                exitY = AUTO,
                settingsX = AUTO,
                settingsY = AUTO,
                streamWidth = current.streamWidth,
                streamHeight = current.streamHeight,
            ),
        )
    }
}
