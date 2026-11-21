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
    private lateinit var previewLabel: TextView
    private lateinit var basisLabel: TextView
    private lateinit var streamLabel: TextView
    private lateinit var basisWField: EditText
    private lateinit var basisHField: EditText
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
        restoreSystemBars()
        setContentView(buildRoot())
        syncFields()
    }

    // ------------------------------------------------------------------ layout

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            // 底部留出固定动作栏的高度，否则最后一段内容会被「返回 / 保存」压住。
            setPadding(dp(20), dp(18), dp(20), dp(96))
        }

        root.addView(TextView(this).apply {
            text = "投屏界面按钮"
            setTextColor(textColor)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "连接成功后，CarLink 投屏画面上会叠这两个小按钮。位置按下方「坐标基准」" +
                "的绝对像素计（左上角为 0,0），留空或填 -1 表示跟随默认布局。"
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
            previewLabel = TextView(this@CarlinkOverlaySettingsActivity).apply {
                text = "坐标基准 ${basisW()}x${basisH()}px"
                setTextColor(subColor)
                textSize = 11f
                setPadding(dp(12), dp(10), 0, 0)
            }
            addView(previewLabel)
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

        // 3. 分辨率基准
        root.addView(sectionTitle("分辨率基准"))
        root.addView(buildResolutionCard())

        // 4. 像素输入
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

        val scroll = ScrollView(this).apply {
            addView(root)
            setBackgroundColor(bg)
            isFillViewport = true
        }
        // 底部固定动作栏：以前这一页只能靠「滑块松手即存」隐式保存，也没有返回按钮，
        // 父页面又是全屏（无系统栏），进去就找不到出口。现在显式给「返回」「保存」。
        return FrameLayout(this).apply {
            setBackgroundColor(bg)
            addView(scroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(buildBottomBar(), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ))
        }
    }

    /** 底部动作栏：左「返回」（不保存改动，直接退出），右「保存」（写盘并保留在本页）。 */
    private fun buildBottomBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(10), dp(20), dp(12))
        setBackgroundColor(bg)
        addView(actionButton("返回", subColor, textColor) { finish() },
            LinearLayout.LayoutParams(0, dp(46), 1f))
        addView(View(this@CarlinkOverlaySettingsActivity).apply { },
            LinearLayout.LayoutParams(dp(12), dp(1)))
        addView(actionButton("保存", accent, Color.WHITE) {
            persist()
            toast("已保存")
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
    }

    private fun actionButton(label: String, fill: Int, ink: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(ink)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(fill, 10, if (fill == accent) accent else border)
            setOnClickListener { onClick() }
        }

    /** 本页要有系统栏：父页面（共享设置宿主）可能按投屏设置隐藏了状态栏/导航栏，
     *  那样子页面既没有返回键也没有可视出口。这里无条件把两条栏放回来。 */
    @Suppress("DEPRECATION")
    private fun restoreSystemBars() {
        // 这里不用 androidx.core：:carlink 只通过 implementation 依赖 :common，拿不到 androidx.core
        // 的编译类路径（上一版就是因此编译失败）。平台 API 足够：清掉全屏标志、把系统栏设为可见。
        runCatching {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // -------------------------------------------------------------- resolution

    /** 坐标基准宽：用户设过就用设的，否则跟随当前窗口。 */
    private fun basisW(): Int = if (prefs.basisWidth > 0) prefs.basisWidth else screenWidth

    private fun basisH(): Int = if (prefs.basisHeight > 0) prefs.basisHeight else screenHeight

    /**
     * 分辨率基准卡片：当前软件分辨率 / 上次投屏分辨率 / 自定义。
     *
     * 为什么需要它：坐标是屏幕绝对像素，而车机面板分辨率和手机编出来的投屏分辨率常常不同
     * （1280×720 对 1920×1080）。用一个明确的基准换算，才不会再出现"在预览里拖到右下角、
     * 实车上跑到屏幕外"。
     */
    private fun buildResolutionCard(): View {
        val cardView = card()
        basisLabel = TextView(this).apply {
            setTextColor(textColor)
            textSize = 14f
            setPadding(0, dp(12), 0, dp(2))
        }
        streamLabel = TextView(this).apply {
            setTextColor(subColor)
            textSize = 12f
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(2))
        }
        presetRow.addView(presetButton("用当前分辨率") {
            applyBasis(screenWidth, screenHeight, "坐标基准已设为当前屏幕")
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        presetRow.addView(View(this@CarlinkOverlaySettingsActivity).apply { },
            LinearLayout.LayoutParams(dp(10), dp(1)))
        presetRow.addView(presetButton("用上次投屏分辨率") {
            val w = prefs.streamWidth
            val h = prefs.streamHeight
            if (w <= 0 || h <= 0) {
                toast("还没有投屏记录，先连一次 CarLink")
            } else {
                applyBasis(w, h, "坐标基准已设为上次投屏 $w x $h")
            }
        }, LinearLayout.LayoutParams(0, dp(42), 1f))

        basisWField = numberField()
        basisHField = numberField()
        val customRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(12))
            addView(TextView(this@CarlinkOverlaySettingsActivity).apply {
                text = "自定义"
                setTextColor(textColor)
                textSize = 14f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(smallLabel("W"))
            addView(basisWField, LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(smallLabel(" H"))
            addView(basisHField, LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val applyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        applyRow.addView(presetButton("应用自定义分辨率") {
            val w = basisWField.text?.toString()?.trim()?.toIntOrNull() ?: 0
            val h = basisHField.text?.toString()?.trim()?.toIntOrNull() ?: 0
            if (w <= 0 || h <= 0) {
                toast("宽高都要填正整数")
            } else {
                applyBasis(w, h, "坐标基准已设为自定义 $w x $h")
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))

        cardView.addView(basisLabel)
        cardView.addView(streamLabel)
        cardView.addView(presetRow)
        cardView.addView(customRow)
        cardView.addView(applyRow)
        updateBasisLabels()
        return cardView
    }

    private fun presetButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(accent)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(6), 0, dp(6), 0)
        background = rounded(card, 9, border)
        setOnClickListener { onClick() }
    }

    /**
     * 切换坐标基准，并把已有坐标从旧基准等比换算到新基准。
     *
     * 不换算的话，改基准会让按钮"跳"到别处：同一组像素在 1280 与 1920 的画面上代表两个位置。
     */
    private fun applyBasis(width: Int, height: Int, note: String) {
        if (width <= 0 || height <= 0) {
            toast("分辨率无效")
            return
        }
        val oldW = basisW()
        val oldH = basisH()
        fun rescale(value: Int, oldSpan: Int, newSpan: Int): Int =
            if (value == CarlinkOverlayPrefs.AUTO || oldSpan <= 0) value
            else (value.toFloat() * newSpan / oldSpan).toInt()

        prefs = prefs.copy(
            exitX = rescale(prefs.exitX, oldW, width),
            exitY = rescale(prefs.exitY, oldH, height),
            settingsX = rescale(prefs.settingsX, oldW, width),
            settingsY = rescale(prefs.settingsY, oldH, height),
            basisWidth = width,
            basisHeight = height,
        )
        syncFields()
        persist()
        applyPreview()
        toast(note)
    }

    private fun updateBasisLabels() {
        if (!::basisLabel.isInitialized) return
        basisLabel.text = "坐标基准 ${basisW()} x ${basisH()} px"
        streamLabel.text = "当前软件分辨率 ${screenWidth}x${screenHeight}" +
            if (prefs.streamWidth > 0 && prefs.streamHeight > 0) {
                "　上次投屏 ${prefs.streamWidth}x${prefs.streamHeight}"
            } else {
                "　上次投屏 未记录"
            }
        if (::basisWField.isInitialized) {
            basisWField.setText(basisW().toString())
            basisHField.setText(basisH().toString())
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
        updateBasisLabels()
        previewLabel.text = "坐标基准 ${basisW()}x${basisH()}px　预览比例 " +
            "${"%.2f".format(previewWidth.toFloat() / basisW())}x"
        footer.text = "基准 ${basisW()}x${basisH()}px：退出 ${ex},${ey}px " +
            "${if (prefs.showExit) "显示" else "隐藏"}" +
            "　设置在 ${sx},${sy}px ${if (prefs.showSettings) "显示" else "隐藏"}"
    }

    private fun resolve(x: Int, y: Int, fallback: Pair<Int, Int>): Pair<Int, Int> =
        (if (x == CarlinkOverlayPrefs.AUTO) fallback.first else x) to
            (if (y == CarlinkOverlayPrefs.AUTO) fallback.second else y)

    private fun placeChip(chip: View, xPx: Int, yPx: Int, visible: Boolean) {
        chip.visibility = if (visible) View.VISIBLE else View.GONE
        // 预览框代表的是「坐标基准」那一张画布，而不是本页窗口——基准和窗口分辨率可能不同。
        val scaleX = previewWidth.toFloat() / basisW()
        val scaleY = previewHeight.toFloat() / basisH()
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
                    val scaleX = previewWidth.toFloat() / basisW()
                    val scaleY = previewHeight.toFloat() / basisH()
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
        updateBasisLabels()
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
