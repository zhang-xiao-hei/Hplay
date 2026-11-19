package com.hpplay.carlink

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.hpplay.CarlinkOverlayPrefs

/**
 * CarLink 投屏界面按钮设置：连接成功后是否出现「退出」/「设置」，以及两个按钮的像素级位置。
 *
 * 为什么单独一个页面：设置主页由公共设置宿主统一渲染，而这块配置只在 CarLink 投屏时才有意义，
 * 单独成页既能让用户在投屏时（长按右上角设置齿轮）随时调，也能给现场工程师提供
 * "填像素坐标 / 拖动预览"两种对齐手段 —— 不同车机的屏幕分辨率、状态栏高度、内饰遮挡都不一样，
 * 用 dp 写死必然对不齐。
 */
class CarlinkOverlaySettingsActivity : com.hpplay.LanguageAwareActivity() {

    private lateinit var prefs: CarlinkOverlayPrefs.Overlay
    private lateinit var footer: TextView

    private lateinit var showExitSwitch: Switch
    private lateinit var showSettingsSwitch: Switch
    private lateinit var exitXField: EditText
    private lateinit var exitYField: EditText
    private lateinit var settingsXField: EditText
    private lateinit var settingsYField: EditText

    private lateinit var preview: FrameLayout
    private lateinit var exitChip: TextView
    private lateinit var settingsChip: TextView
    private var previewWidth = 0
    private var previewHeight = 0
    private var screenWidth = 1
    private var screenHeight = 1

    private var bg = Color.parseColor("#0F1418")
    private var card = Color.parseColor("#1A2026")
    private var textColor = Color.parseColor("#E7EDF3")
    private var subColor = Color.parseColor("#93A0AC")
    private var accent = Color.parseColor("#4C8DFF")
    private var border = Color.parseColor("#2A333C")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.hpplay.ConsentGate.canProceed(this)) {
            finish()
            return
        }
        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (!dark) {
            bg = Color.parseColor("#F4F6F9")
            card = Color.parseColor("#FFFFFF")
            textColor = Color.parseColor("#1B2026")
            subColor = Color.parseColor("#66727E")
            accent = Color.parseColor("#2F6FE4")
            border = Color.parseColor("#DCE2E8")
        }
        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels.coerceAtLeast(1)
        screenHeight = dm.heightPixels.coerceAtLeast(1)
        prefs = CarlinkOverlayPrefs.load(this)
        setContentView(buildRoot())
        syncFields()
    }

    // ------------------------------------------------------------------ layout

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }

        root.addView(TextView(this).apply {
            text = "投屏界面按钮"
            setTextColor(textColor)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "连接成功后，CarLink 投屏画面上会叠这两个小按钮。位置按屏幕绝对像素计" +
                "（左上角为 0,0），留空或填 -1 表示跟随默认布局。"
            setTextColor(subColor)
            textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(8), 0, dp(14))
        })

        // 1. 开关
        val switchCard = card()
        val exitRow = switchRow(
            "连接成功后显示「退出」按钮",
            "点击断开投屏并回到协议选择页",
            prefs.showExit,
        ) { checked ->
            prefs = prefs.copy(showExit = checked)
            persist()
            applyPreview()
        }
        showExitSwitch = exitRow.second
        switchCard.addView(exitRow.first)
        switchCard.addView(divider())
        val settingsRow = switchRow(
            "连接成功后显示「设置」按钮",
            "打开共享设置页；长按它进入本页",
            prefs.showSettings,
        ) { checked ->
            prefs = prefs.copy(showSettings = checked)
            persist()
            applyPreview()
        }
        showSettingsSwitch = settingsRow.second
        switchCard.addView(settingsRow.first)
        root.addView(switchCard)

        // 2. 预览（可直接拖动）
        root.addView(sectionTitle("拖动预览（松手即保存）"))
        preview = FrameLayout(this).apply {
            setBackgroundDrawable(rounded(card, 14, border))
            addView(TextView(this@CarlinkOverlaySettingsActivity).apply {
                text = "车机屏幕 ${screenWidth}x${screenHeight}"
                setTextColor(subColor)
                textSize = 11f
                setPadding(dp(12), dp(10), 0, 0)
            })
        }
        root.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(220),
        ).apply { topMargin = dp(6) })
        exitChip = chip("退出", accent).also { preview.addView(it) }
        settingsChip = chip("设置", Color.parseColor("#6B7B8C")).also { preview.addView(it) }
        attachDrag(exitChip, isExit = true)
        attachDrag(settingsChip, isExit = false)
        preview.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            previewWidth = (r - l)
            previewHeight = (b - t)
            applyPreview()
        }

        // 3. 像素输入
        root.addView(sectionTitle("像素坐标"))
        val posCard = card()
        exitXField = numberField()
        exitYField = numberField()
        settingsXField = numberField()
        settingsYField = numberField()
        posCard.addView(inputRow("退出按钮", exitXField, exitYField) {
            prefs = prefs.copy(
                exitX = parsed(exitXField, prefs.exitX),
                exitY = parsed(exitYField, prefs.exitY),
            )
            persist(); applyPreview()
        })
        posCard.addView(divider())
        posCard.addView(inputRow("设置按钮", settingsXField, settingsYField) {
            prefs = prefs.copy(
                settingsX = parsed(settingsXField, prefs.settingsX),
                settingsY = parsed(settingsYField, prefs.settingsY),
            )
            persist(); applyPreview()
        })
        root.addView(posCard)

        root.addView(TextView(this).apply {
            text = "恢复默认：两个按钮都显示，位置回到内置布局"
            setTextColor(accent)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(16), 0, dp(4))
            setOnClickListener {
                CarlinkOverlayPrefs.reset(this@CarlinkOverlaySettingsActivity)
                prefs = CarlinkOverlayPrefs.load(this@CarlinkOverlaySettingsActivity)
                showExitSwitch.isChecked = prefs.showExit
                showSettingsSwitch.isChecked = prefs.showSettings
                syncFields()
                applyPreview()
                toast("已恢复默认")
            }
        })

        footer = TextView(this).apply {
            setTextColor(subColor)
            textSize = 12f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(14), 0, dp(4))
        }
        root.addView(footer)

        return ScrollView(this).apply {
            addView(root)
            setBackgroundColor(bg)
        }
    }

    private fun sectionTitle(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(textColor)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(4), dp(18), 0, dp(8))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = rounded(card, 14, border)
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(border)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1),
        )
    }

    private fun switchRow(
        title: String,
        desc: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
    ): Pair<View, Switch> {
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onToggle(value) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(LinearLayout(this@CarlinkOverlaySettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@CarlinkOverlaySettingsActivity).apply {
                    text = title
                    setTextColor(textColor)
                    textSize = 14.5f
                })
                addView(TextView(this@CarlinkOverlaySettingsActivity).apply {
                    text = desc
                    setTextColor(subColor)
                    textSize = 12f
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
        }
        return row to sw
    }

    private fun inputRow(
        label: String,
        xField: EditText,
        yField: EditText,
        onCommit: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        addView(TextView(this@CarlinkOverlaySettingsActivity).apply {
            text = label
            setTextColor(textColor)
            textSize = 14f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(smallLabel("X"))
        addView(xField, LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(smallLabel("  Y"))
        addView(yField, LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT))
        xField.setOnFocusChangeListener { _, focused -> if (!focused) onCommit() }
        yField.setOnFocusChangeListener { _, focused -> if (!focused) onCommit() }
    }

    private fun smallLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(subColor)
        textSize = 12f
        setPadding(dp(6), 0, dp(4), 0)
    }

    private fun numberField(): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        textSize = 14f
        setTextColor(textColor)
        background = rounded(card, 8, border)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setSingleLine(true)
    }

    private fun chip(label: String, color: Int): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(color)
        }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    // ------------------------------------------------------------------ preview

    /** 默认位置（与宿主内置布局一致：退出左上、设置在右上第二行）。 */
    private fun defaultExitPx(): Pair<Int, Int> = dp(16) to dp(14)
    private fun defaultSettingsPx(): Pair<Int, Int> = (screenWidth - dp(48) - dp(16)) to dp(72)

    private fun applyPreview() {
        if (previewWidth <= 0 || previewHeight <= 0) return
        if (!::footer.isInitialized) return
        val (ex, ey) = if (prefs.exitX != CarlinkOverlayPrefs.AUTO || prefs.exitY != CarlinkOverlayPrefs.AUTO) {
            resolve(prefs.exitX, prefs.exitY, defaultExitPx())
        } else defaultExitPx()
        val (sx, sy) = if (prefs.settingsX != CarlinkOverlayPrefs.AUTO || prefs.settingsY != CarlinkOverlayPrefs.AUTO) {
            resolve(prefs.settingsX, prefs.settingsY, defaultSettingsPx())
        } else defaultSettingsPx()

        placeChip(exitChip, ex, ey, prefs.showExit)
        placeChip(settingsChip, sx, sy, prefs.showSettings)
        footer.text = "当前：退出 ${ex},${ey}px ${if (prefs.showExit) "显示" else "隐藏"}" +
            "　设置在 ${sx},${sy}px ${if (prefs.showSettings) "显示" else "隐藏"}"
    }

    private fun resolve(x: Int, y: Int, fallback: Pair<Int, Int>): Pair<Int, Int> =
        (if (x == CarlinkOverlayPrefs.AUTO) fallback.first else x) to
            (if (y == CarlinkOverlayPrefs.AUTO) fallback.second else y)

    private fun placeChip(chip: View, xPx: Int, yPx: Int, visible: Boolean) {
        chip.visibility = if (visible) View.VISIBLE else View.GONE
        val scaleX = previewWidth.toFloat() / screenWidth
        val scaleY = previewHeight.toFloat() / screenHeight
        val lp = (chip.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(dp(56), dp(30)))
        lp.leftMargin = (xPx * scaleX).toInt()
        lp.topMargin = (yPx * scaleY).toInt()
        chip.layoutParams = lp
    }

    /** 预览里拖动 → 反算屏幕像素 → 落盘。 */
    private fun attachDrag(chip: View, isExit: Boolean) {
        var downX = 0f
        var downY = 0f
        var startMarginX = 0
        var startMarginY = 0
        chip.setOnTouchListener { view, event ->
            val lp = view.layoutParams as FrameLayout.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startMarginX = lp.leftMargin
                    startMarginY = lp.topMargin
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (previewWidth <= 0 || previewHeight <= 0) return@setOnTouchListener false
                    val scaleX = previewWidth.toFloat() / screenWidth
                    val scaleY = previewHeight.toFloat() / screenHeight
                    val newMarginX = (startMarginX + (event.rawX - downX)).toInt()
                        .coerceIn(0, previewWidth - view.width)
                    val newMarginY = (startMarginY + (event.rawY - downY)).toInt()
                        .coerceIn(0, previewHeight - view.height)
                    lp.leftMargin = newMarginX
                    lp.topMargin = newMarginY
                    view.layoutParams = lp
                    val px = (newMarginX / scaleX).toInt()
                    val py = (newMarginY / scaleY).toInt()
                    if (isExit) {
                        prefs = prefs.copy(exitX = px, exitY = py)
                        exitXField.setText(px.toString())
                        exitYField.setText(py.toString())
                    } else {
                        prefs = prefs.copy(settingsX = px, settingsY = py)
                        settingsXField.setText(px.toString())
                        settingsYField.setText(py.toString())
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    persist()
                    applyPreview()
                    true
                }
                else -> false
            }
        }
        // 单击也能选中该按钮，避免开关关掉后无法再拖动。
        chip.setOnClickListener { toast(if (isExit) "退出按钮" else "设置按钮") }
        chip.isClickable = true
    }

    // ------------------------------------------------------------------ helpers

    private fun syncFields() {
        exitXField.setText(if (prefs.exitX == CarlinkOverlayPrefs.AUTO) "" else prefs.exitX.toString())
        exitYField.setText(if (prefs.exitY == CarlinkOverlayPrefs.AUTO) "" else prefs.exitY.toString())
        settingsXField.setText(
            if (prefs.settingsX == CarlinkOverlayPrefs.AUTO) "" else prefs.settingsX.toString(),
        )
        settingsYField.setText(
            if (prefs.settingsY == CarlinkOverlayPrefs.AUTO) "" else prefs.settingsY.toString(),
        )
        showExitSwitch.isChecked = prefs.showExit
        showSettingsSwitch.isChecked = prefs.showSettings
    }

    private fun parsed(field: EditText, current: Int): Int {
        val raw = field.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty() || raw == "-") return current
        return raw.toIntOrNull() ?: current
    }

    private fun persist() {
        CarlinkOverlayPrefs.save(this, prefs)
        com.hpplay.SessionLogger.log(
            "carlink",
            "overlay prefs: exit=${prefs.showExit}@${prefs.exitX},${prefs.exitY} " +
                "settings=${prefs.showSettings}@${prefs.settingsX},${prefs.settingsY}",
        )
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
