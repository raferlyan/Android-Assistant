package com.xiaoyan.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
 * 调试界面：上半部分看实时状态（唤醒次数、音量、识别结果、回复、日志），
 * 下半部分调参数，点「保存并重启引擎」立即生效。
 *
 * 也可以不说话直接测意图：`adb shell am start -n com.xiaoyan.assistant/.MainActivity --es cmd 打开手电筒`
 */
class MainActivity : Activity(), AssistantBus.Listener {

    private lateinit var stateView: TextView
    private lateinit var statsView: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var partialView: TextView
    private lateinit var finalView: TextView
    private lateinit var replyView: TextView
    private lateinit var cmdEdit: EditText
    private lateinit var permView: TextView
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
    private lateinit var deviceSpinner: Spinner
    private lateinit var overlayBox: CheckBox
    private lateinit var bargeInBox: CheckBox
    private lateinit var aecBox: CheckBox
    private lateinit var llmBox: CheckBox
    private lateinit var presetSpinner: Spinner
    private lateinit var llmUrlEdit: EditText
    private lateinit var llmModelEdit: EditText
    private lateinit var llmKeyEdit: EditText
    private val sliders = LinkedHashMap<String, Slider>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        bindSettings(AppSettings.load(this))
        requestPermissionsIfNeeded()
        handleCmd(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCmd(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun handleCmd(i: Intent?) {
        val cmd = i?.getStringExtra("cmd") ?: return
        i.removeExtra("cmd")
        AssistantService.send(this, AssistantService.ACTION_TEXT, cmd)
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
                EngineState.RECOGNIZING, EngineState.WAITING -> 0xFF6A1B9A.toInt()
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

    override fun onReply(text: String) {
        replyView.text = text
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
        sliders["followUpSec"]!!.value = s.followUpSec
        deviceSpinner.setSelection(s.deviceMode.ordinal)
        overlayBox.isChecked = s.showOverlay
        bargeInBox.isChecked = s.bargeIn
        aecBox.isChecked = s.echoCancel
        llmBox.isChecked = s.llmEnabled
        llmUrlEdit.setText(s.llmBaseUrl)
        llmModelEdit.setText(s.llmModel)
        llmKeyEdit.setText(s.llmApiKey)
        val preset = LlmPreset.entries.firstOrNull { it.baseUrl == s.llmBaseUrl } ?: LlmPreset.CUSTOM
        presetSpinner.tag = preset // 绑定时不要覆盖用户改过的模型名
        presetSpinner.setSelection(preset.ordinal)
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
        deviceMode = DeviceMode.entries[deviceSpinner.selectedItemPosition],
        showOverlay = overlayBox.isChecked,
        bargeIn = bargeInBox.isChecked,
        echoCancel = aecBox.isChecked,
        followUpSec = sliders["followUpSec"]!!.value,
        llmEnabled = llmBox.isChecked,
        llmBaseUrl = llmUrlEdit.text.toString().trim(),
        llmModel = llmModelEdit.text.toString().trim(),
        llmApiKey = llmKeyEdit.text.toString().trim(),
    )

    private fun refreshPermissions() {
        val overlay = if (Settings.canDrawOverlays(this)) "✓ 悬浮窗" else "✗ 悬浮窗（浮窗、后台打开应用都需要）"
        val write = if (Settings.System.canWrite(this)) "✓ 修改系统设置" else "✗ 修改系统设置（调亮度需要）"
        permView.text = "$overlay\n$write"
    }

    private fun openPermission(action: String) =
        startActivity(Intent(action, Uri.parse("package:$packageName")))

    /** 后台线程发一句「你好」，验证地址、模型、key 是否可用。 */
    private fun testLlm() {
        val s = collectSettings().copy(llmEnabled = true)
        val client = LlmClient.from(s)
        if (client == null) {
            Toast.makeText(this, "地址、模型或 key 为空", Toast.LENGTH_SHORT).show()
            return
        }
        AssistantBus.log("测试大模型：${s.llmModel} @ ${s.llmBaseUrl}")
        Thread {
            val t0 = System.currentTimeMillis()
            val r = runCatching { client.chat(listOf("user" to "用一句话介绍你自己"), 60) }
            val ms = System.currentTimeMillis() - t0
            AssistantBus.log(r.fold({ "✓ 大模型可用（${ms}ms）：$it" }, { "✗ 大模型请求失败（${ms}ms）：${it.message}" }))
        }.start()
    }

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
        replyView = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF2E7D32.toInt())
            minHeight = dp(28)
        }
        root.addView(label("助手回复"))
        root.addView(replyView, matchWrap())

        val cmdRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        cmdEdit = EditText(this).apply {
            setSingleLine()
            hint = "输入文字测试意图，如：音量调到五十"
        }
        cmdRow.addView(cmdEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cmdRow.addView(Button(this).apply {
            text = "发送"
            setOnClickListener {
                val t = cmdEdit.text.toString().trim()
                if (t.isNotEmpty()) AssistantService.send(this@MainActivity, AssistantService.ACTION_TEXT, t)
            }
        })
        root.addView(cmdRow, matchWrap())

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

        // ---- 交互
        root.addView(section("交互 / 浮窗"))
        deviceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, DeviceMode.entries.map { it.label },
            )
        }
        root.addView(label("设备形态（决定回复话术和浮窗位置：车机在顶部，手机在底部）"))
        root.addView(deviceSpinner, matchWrap())
        overlayBox = CheckBox(this).apply { text = "唤醒后显示胶囊浮窗" }
        root.addView(overlayBox)
        bargeInBox = CheckBox(this).apply { text = "播报时说唤醒词可打断（点胶囊也能打断）" }
        root.addView(bargeInBox)
        aecBox = CheckBox(this).apply { text = "录音开启回声消除（打断更灵敏，可能影响识别率）" }
        root.addView(aecBox)
        addSlider(root, "followUpSec", "回复后继续聆听（秒，期间不用再唤醒；0 = 不继续）", 0f, 15f, 1f)
        permView = TextView(this).apply { textSize = 13f; setPadding(0, dp(8), 0, 0) }
        root.addView(permView)
        val permRow = LinearLayout(this)
        permRow.addView(Button(this).apply {
            text = "授予悬浮窗权限"
            setOnClickListener { openPermission(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        permRow.addView(Button(this).apply {
            text = "授予修改系统设置"
            setOnClickListener { openPermission(Settings.ACTION_MANAGE_WRITE_SETTINGS) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(permRow, matchWrap())

        // ---- 大模型
        root.addView(section("在线大模型（本地规则匹配不上时调用）"))
        llmBox = CheckBox(this).apply { text = "启用在线大模型（只发送识别出的文字）" }
        root.addView(llmBox)
        llmUrlEdit = EditText(this).apply { setSingleLine(); textSize = 14f }
        llmModelEdit = EditText(this).apply { setSingleLine(); textSize = 14f }
        presetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, LlmPreset.entries.map { it.label },
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val preset = LlmPreset.entries[pos]
                    if (tag == preset) { tag = null; return }
                    tag = null
                    if (preset == LlmPreset.CUSTOM) return
                    llmUrlEdit.setText(preset.baseUrl)
                    llmModelEdit.setText(preset.model)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(label("服务商（OpenAI 兼容接口）"))
        root.addView(presetSpinner, matchWrap())
        root.addView(label("接口地址（会拼上 /chat/completions）"))
        root.addView(llmUrlEdit, matchWrap())
        root.addView(label("模型"))
        root.addView(llmModelEdit, matchWrap())
        llmKeyEdit = EditText(this).apply {
            setSingleLine()
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = if (BuildConfig.LLM_DEBUG_KEY.isNotEmpty()) "留空 = 使用编译时的调试 key" else "sk-..."
        }
        root.addView(label("API Key"))
        root.addView(llmKeyEdit, matchWrap())
        root.addView(Button(this).apply {
            text = "测试连接"
            setOnClickListener { testLlm() }
        }, matchWrap())

        // ---- 唤醒
        root.addView(section("语音唤醒"))
        keywordsEdit = multiline(3)
        root.addView(label("唤醒词（每行一个，可选 :增强分 #阈值；多音字可直接写 token，如 x iǎo y àn x iǎo y àn @小晏小晏）"))
        root.addView(keywordsEdit, matchWrap())
        addSlider(root, "kwsThreshold", "全局唤醒阈值（小→易唤醒/易误唤醒）", 0.05f, 0.6f, 0.01f)
        addSlider(root, "kwsScore", "全局增强分数（大→易唤醒）", 0.5f, 4f, 0.1f)
        addSlider(root, "kwsTrailingBlanks", "尾随 blank 帧数（大→更稳，响应稍慢）", 0f, 8f, 1f)
        testModeBox = CheckBox(this).apply { text = "唤醒测试模式（只统计唤醒次数，不进入识别）" }
        beepBox = CheckBox(this).apply { text = "唤醒提示音（「我在」之前再响一声）" }
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
        speakBox = CheckBox(this).apply { text = "TTS 语音回复（含唤醒后的「我在」）" }
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
