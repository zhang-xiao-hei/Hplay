package com.hpplay.carlife.theme

import android.app.Activity
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * CarLife 车机端日间/夜间判定。
 *
 * CarLife 的日夜切换是**车机侧的责任**：手机端不会自己判断，车机必须通过 CMD 通道
 * `HU_THEME_CHANGE(0x18078)` 告知，手机据此重绘界面。参考实现只在编码器协商那一刻
 * 下发一次写死的 `{1}`（夜间），于是：
 *  - 白天也停在夜间深色皮肤；
 *  - 之后系统切深色、进隧道、开大灯，手机端都收不到任何通知 —— 用户看到的就是"日夜切换不生效"。
 *
 * 这里把"当前该用哪种主题"变成一个持续跟踪的状态，优先级：
 *  外部强制（intent `--ei theme 0|1`，给车机集成方按大灯/CAN 信号切换）
 *  > 环境光（有光照传感器时，带迟滞避免隧道口抖动）
 *  > 系统深色模式（UiModeManager / 设置里的深色主题）
 *
 * 判定变化后通过 [onMode] 回调（0=日间、1=夜间），由上层决定何时真正发给手机。
 */
class CarLifeDayNight(
    private val context: Context,
    private val onMode: (Int) -> Unit,
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val DAY = 0
        const val NIGHT = 1

        /** 进入夜间的光照阈值（lux）：低于它判夜间。 */
        private const val NIGHT_LUX = 20f

        /** 回到日间的光照阈值（lux）：高于它判日间。中间区间不动作（迟滞）。 */
        private const val DAY_LUX = 60f

        /** 外部强制主题的 intent extra 名（与 CarlifeHostActivity 的口径一致）。 */
        const val EXTRA_THEME = "theme"

        private const val TAG = "CarlifeDayNight"
    }

    @Volatile private var nightBySystem = false
    @Volatile private var nightByLight = false
    private var forced: Int? = null
    private var lastApplied = -1
    private var sensorManager: SensorManager? = null
    private var lightListener: SensorEventListener? = null

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val dark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            if (dark != nightBySystem) {
                nightBySystem = dark
                apply("system uiMode -> night=$dark")
            }
        }

        override fun onLowMemory() = Unit
    }

    /** 开始跟踪（可重复调用；已在跟踪时只重算一次）。 */
    fun start() {
        nightBySystem = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        forced = readForcedTheme()
        runCatching { context.applicationContext.registerComponentCallbacks(configCallbacks) }
            .onFailure { Log.w(TAG, "register config callbacks failed: ${it.message}") }
        registerLightSensor()
        apply("start")
    }

    fun stop() {
        unregisterLightSensor()
        runCatching { context.applicationContext.unregisterComponentCallbacks(configCallbacks) }
    }

    /**
     * 外部强制主题。
     *
     * @param mode 0=日间、1=夜间；传 null 恢复自动（系统深色 + 环境光）。
     */
    fun setForced(mode: Int?) {
        forced = mode?.takeIf { it == DAY || it == NIGHT }
        apply("forced=$forced")
    }

    /** 当前应使用的主题（0=日间、1=夜间）。 */
    fun currentMode(): Int = forced ?: if (nightByLight || nightBySystem) NIGHT else DAY

    private fun apply(reason: String) {
        val mode = currentMode()
        if (mode == lastApplied) return
        lastApplied = mode
        val text = "theme=$mode ($reason, sysNight=$nightBySystem, lightNight=$nightByLight, " +
            "forced=${forced?.toString() ?: "-"})"
        Log.i(TAG, text)
        log(text)
        onMode(mode)
    }

    private fun readForcedTheme(): Int? = runCatching {
        (context as? Activity)?.intent?.getIntExtra(EXTRA_THEME, -1)
    }.getOrNull()?.takeIf { it == DAY || it == NIGHT }

    private fun registerLightSensor() {
        if (forced != null) {
            Log.i(TAG, "theme forced by intent; ambient light auto-switch disabled")
            return
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sensorManager = manager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_LIGHT) ?: run {
            Log.i(TAG, "no ambient light sensor; day/night follows system dark mode")
            return
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val lux = event.values.firstOrNull() ?: return
                val night = when {
                    lux <= NIGHT_LUX -> true
                    lux >= DAY_LUX -> false
                    else -> return
                }
                if (night != nightByLight) {
                    nightByLight = night
                    apply("ambient ${"%.1f".format(lux)}lux -> night=$night")
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        runCatching { manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
            .onSuccess {
                lightListener = listener
                Log.i(TAG, "ambient light sensor active (night<=${NIGHT_LUX}lux, day>=${DAY_LUX}lux)")
            }
            .onFailure { Log.w(TAG, "register light sensor failed: ${it.message}") }
    }

    private fun unregisterLightSensor() {
        val listener = lightListener ?: return
        runCatching { sensorManager?.unregisterListener(listener) }
        lightListener = null
    }
}
