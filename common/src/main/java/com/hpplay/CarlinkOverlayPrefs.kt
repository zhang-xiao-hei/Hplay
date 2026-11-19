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
 *  - 其余值就是**屏幕绝对像素**（相对宿主根布局左上角），与车机分辨率一一对应，
 *    用户量出来的坐标可以直接填。
 */
object CarlinkOverlayPrefs {

    private const val FILE = "carlink_overlay"
    private const val KEY_SHOW_EXIT = "show_exit"
    private const val KEY_SHOW_SETTINGS = "show_settings"
    private const val KEY_EXIT_X = "exit_x"
    private const val KEY_EXIT_Y = "exit_y"
    private const val KEY_SETTINGS_X = "settings_x"
    private const val KEY_SETTINGS_Y = "settings_y"

    /** 位置未设置：跟随宿主默认布局。 */
    const val AUTO = -1

    data class Overlay(
        val showExit: Boolean,
        val showSettings: Boolean,
        val exitX: Int,
        val exitY: Int,
        val settingsX: Int,
        val settingsY: Int,
    ) {
        /** 退出按钮是否给了绝对坐标。 */
        val exitHasPosition: Boolean get() = exitX != AUTO || exitY != AUTO

        /** 设置按钮是否给了绝对坐标。 */
        val settingsHasPosition: Boolean get() = settingsX != AUTO || settingsY != AUTO
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

    /** 恢复默认：两个按钮都显示，位置回落到宿主默认布局。 */
    fun reset(context: Context) {
        save(context, Overlay(true, true, AUTO, AUTO, AUTO, AUTO))
    }
}
