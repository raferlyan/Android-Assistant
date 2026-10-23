package com.xiaoyan.assistant

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * 调试界面：上半部分看实时状态（唤醒次数、音量、识别结果、日志），
 * 下半部分调参数，点「保存并重启引擎」立即生效。
 */
class MainActivity : Activity(), AssistantBus.Listener {

    private lateinit var stateView: TextView
    private lateinit var statsView: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var partialView: TextView
    private lateinit var finalView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private lateinit var modeSpinner: Spinner
    private lateinit var keywordsEdit: EditText
    private lateinit var hotwordsEdit: EditText
    private lateinit var correctionsEdit: EditText
    private lateinit var svLangEdit: EditText
    private lateinit var testModeBox: CheckBox
    private lateinit var beepBox: CheckBox
    private lateinit var speakBox: CheckBox
    private lateinit var saveAudioBox: CheckBox
    private val sliders = LinkedHashMap<String, Slider>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        bindSettings(AppSettings.load(this))
        requestPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        AssistantBus.add(this)
        onState(AssistantBus.state)
        onStats(AssistantBus.wakeCount)
        logView.text = AssistantBus.logs.joinToString("\n")
        scrollLogToEnd()
    }

    override fun onStop() {
        AssistantBus.remove(this)
        super.onStop()
    }

    // ---------------------------------------------------------------- 事件

    override fun onState(state: EngineState) {
        stateView.text = state.label
        stateView.setBackgroundColor(
            when (state) {
                EngineState.IDLE -> 0xFF2E7D32.toInt()
                EngineState.LISTENING -> 0xFF1565C0.toInt()
                EngineState.RECOGNIZING -> 0xFF6A1B9A.toInt()
                EngineState.ERROR -> 0xFFC62828.toInt()
                else -> 0xFF616161.toInt()
            },
        )
    }

    override fun onStats(wakeCount: Int) {
        statsView.text = "唤醒次数：$wakeCount"
    }

    override fun onLevel(db: Float) {
        levelBar.progress = ((db + 70f) / 70f * 100).toInt().coerceIn(0, 100)
    }

    override fun onPartial(text: String) {
        partialView.text = text
    }

    override fun onFinal(text: String) {
        partialView.text = ""
        if (text.isNotEmpty()) finalView.text = text
    }

    override fun onLog(line: String) {
        logView.append(if (logView.length() == 0) line else "\n$line")
        if (logView.lineCount > 400) logView.text = AssistantBus.logs.joinToString("\n")
        scrollLogToEnd()
    }

    private fun scrollLogToEnd() = logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

    // ---------------------------------------------------------------- 设置

    private fun bindSettings(s: AppSettings) {
        modeSpinner.setSelection(s.asrMode.ordinal)
        keywordsEdit.setText(s.keywords)
        hotwordsEdit.setText(s.hotwords)
        correctionsEdit.setText(s.corrections)
        svLangEdit.setText(s.senseVoiceLang)
        testModeBox.isChecked = s.kwsTestMode
        beepBox.isChecked = s.beepOnWake
        speakBox.isChecked = s.speakReply
        saveAudioBox.isChecked = s.saveAudio
        sliders["kwsThreshold"]!!.value = s.kwsThreshold
        sliders["kwsScore"]!!.value = s.kwsScore
        sliders["kwsTrailingBlanks"]!!.value = s.kwsTrailingBlanks.toFloat()
        sliders["hotwordsScore"]!!.value = s.hotwordsScore
        sliders["endSilenceSec"]!!.value = s.endSilenceSec
        sliders["vadThreshold"]!!.value = s.vadThreshold
        sliders["listenTimeoutSec"]!!.value = s.listenTimeoutSec
        sliders["numThreads"]!!.value = s.numThreads.toFloat()
    }

    private fun collectSettings() = AppSettings(
        keywords = keywordsEdit.text.toString(),
        kwsThreshold = sliders["kwsThreshold"]!!.value,
        kwsScore = sliders["kwsScore"]!!.value,
        kwsTrailingBlanks = sliders["kwsTrailingBlanks"]!!.value.toInt(),
        kwsTestMode = testModeBox.isChecked,
        asrMode = AsrMode.entries[modeSpinner.selectedItemPosition],
        hotwords = hotwordsEdit.text.toString(),
        hotwordsScore = sliders["hotwordsScore"]!!.value,
        corrections = correctionsEdit.text.toString(),
        senseVoiceLang = svLangEdit.text.toString().trim().ifEmpty { "auto" },
        listenTimeoutSec = sliders["listenTimeoutSec"]!!.value,
        endSilenceSec = sliders["endSilenceSec"]!!.value,
        vadThreshold = sliders["vadThreshold"]!!.value,
        numThreads = sliders["numThreads"]!!.value.toInt(),
        beepOnWake = beepBox.isChecked,
        speakReply = speakBox.isChecked,
        saveAudio = saveAudioBox.isChecked,
    )

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val need = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startEngine() {
        if (!hasMic()) {
            Toast.makeText(this, "需要录音权限", Toast.LENGTH_SHORT).show()
            requestPermissionsIfNeeded()
            return
        }
        collectSettings().save(this)
        AssistantService.send(this, AssistantService.ACTION_START)
    }

    // ---------------------------------------------------------------- 布局（纯代码，免得引 AndroidX）

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        stateView = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        root.addView(stateView, matchWrap())

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        statsView = TextView(this).apply { textSize = 14f }
        row.addView(statsView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "清零"
            setOnClickListener { AssistantBus.resetStats() }
        })
        root.addView(row, matchWrap())

        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(label("麦克风电平"))
        root.addView(levelBar, matchWrap())

        partialView = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF1565C0.toInt())
            minHeight = dp(28)
        }
        finalView = TextView(this).apply {
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dp(36)
        }
        root.addView(label("实时识别"))
        root.addView(partialView, matchWrap())
        root.addView(label("最终结果"))
        root.addView(finalView, matchWrap())

        val buttons = LinearLayout(this)
        buttons.addView(Button(this).apply {
            text = "保存并(重)启动"
            setOnClickListener { startEngine() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "停止"
            setOnClickListener { AssistantService.send(this@MainActivity, AssistantService.ACTION_STOP) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "恢复默认"
            setOnClickListener { bindSettings(AppSettings()) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons, matchWrap())

        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        logScroll = ScrollView(this).apply {
            setBackgroundColor(0xFFF1F1F1.toInt())
            addView(logView)
            // 日志区自己滚动，不和外层抢手势
            setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }
        }
        root.addView(label("日志（logcat 过滤 tag: XiaoYan）"))
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))

        // ---- 唤醒
        root.addView(section("语音唤醒"))
        keywordsEdit = multiline(3)
        root.addView(label("唤醒词（每行一个，可选 :增强分 #阈值；多音字可直接写 token，如 x iǎo y àn x iǎo y àn @小晏小晏）"))
        root.addView(keywordsEdit, matchWrap())
        addSlider(root, "kwsThreshold", "全局唤醒阈值（小→易唤醒/易误唤醒）", 0.05f, 0.6f, 0.01f)
        addSlider(root, "kwsScore", "全局增强分数（大→易唤醒）", 0.5f, 4f, 0.1f)
        addSlider(root, "kwsTrailingBlanks", "尾随 blank 帧数（大→更稳，响应稍慢）", 0f, 8f, 1f)
        testModeBox = CheckBox(this).apply { text = "唤醒测试模式（只统计唤醒次数，不进入识别）" }
        beepBox = CheckBox(this).apply { text = "唤醒提示音" }
        root.addView(testModeBox)
        root.addView(beepBox)

        // ---- 识别
        root.addView(section("语音识别 / 方言"))
        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, AsrMode.entries.map { it.label },
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    svLangEdit.isEnabled = AsrMode.entries[pos] == AsrMode.SENSEVOICE
                    hotwordsEdit.isEnabled = AsrMode.entries[pos] == AsrMode.MANDARIN_STREAMING
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(label("识别模式"))
        root.addView(modeSpinner, matchWrap())
        svLangEdit = EditText(this).apply { setSingleLine() }
        root.addView(label("SenseVoice 语种：auto / zh / yue / en / ja / ko"))
        root.addView(svLangEdit, matchWrap())
        addSlider(root, "endSilenceSec", "句尾静音判定（秒）", 0.3f, 2.0f, 0.1f)
        addSlider(root, "vadThreshold", "VAD 阈值（方言模式；小→灵敏，远场/小声调低）", 0.1f, 0.7f, 0.05f)
        addSlider(root, "listenTimeoutSec", "唤醒后无语音超时（秒）", 2f, 15f, 0.5f)
        addSlider(root, "numThreads", "识别线程数", 1f, 4f, 1f)

        // ---- 关键词调优
        root.addView(section("关键词识别调优"))
        hotwordsEdit = multiline(4)
        root.addView(label("热词（仅普通话流式；每行一个，可写 :分数）"))
        root.addView(hotwordsEdit, matchWrap())
        addSlider(root, "hotwordsScore", "全局热词分数（大→更偏向热词）", 0.5f, 5f, 0.1f)
        correctionsEdit = multiline(4)
        root.addView(label("纠错替换（每行 错=>对，所有模式生效）"))
        root.addView(correctionsEdit, matchWrap())

        root.addView(section("其他"))
        speakBox = CheckBox(this).apply { text = "TTS 语音回复" }
        saveAudioBox = CheckBox(this).apply { text = "保存每条指令录音（files/recordings）" }
        root.addView(speakBox)
        root.addView(saveAudioBox)

        return ScrollView(this).apply { addView(root) }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(0xFF757575.toInt())
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun multiline(lines: Int) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = lines
        gravity = Gravity.TOP
        textSize = 14f
    }

    private fun addSlider(root: LinearLayout, key: String, title: String, min: Float, max: Float, step: Float) {
        val s = Slider(title, min, max, step)
        sliders[key] = s
        root.addView(s.label)
        root.addView(s.bar, matchWrap())
    }

    private inner class Slider(val title: String, val min: Float, max: Float, val step: Float) {
        val label = this@MainActivity.label(title)
        val bar = SeekBar(this@MainActivity).apply {
            this.max = ((max - min) / step).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = refresh()
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        var value: Float
            get() = min + bar.progress * step
            set(v) { bar.progress = ((v - min) / step + 0.5f).toInt(); refresh() }

        private fun refresh() {
            label.text = "$title：${if (step >= 1f) value.toInt().toString() else "%.2f".format(value)}"
        }
    }
}
