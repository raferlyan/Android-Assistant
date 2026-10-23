package com.raferlyan.hark

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一次语音会话的调度：唤醒 → 播报「我在」→ 聆听 → 意图路由（本地执行 / 在线大模型）→ 播报回复
 * → 在 [AppSettings.followUpSec] 秒内继续聆听下一句，没人说话就收起浮窗。
 *
 * 打断：播报回复或等大模型时，说唤醒词（引擎回调 [onBargeIn]）或点一下胶囊，
 * 都会立刻停掉播报、丢弃还没回来的大模型结果，直接进入聆听。
 *
 * 引擎回调来自引擎线程，这里统一切到主线程处理；只有 TTS 的「我在」直接在引擎线程发出，
 * 保证麦克风静音和唤醒是同一帧生效。
 */
class VoiceSession(private val ctx: Context, private val s: AppSettings) : AssistantEngine.Callbacks {

    lateinit var engine: AssistantEngine

    private val main = Handler(Looper.getMainLooper())
    private val phrases = Phrases(s.deviceMode)
    private val speaker = Speaker(ctx, s.speakReply)
    private val llm = LlmClient.from(s)
    private val router = IntentRouter(DeviceActions(ctx), phrases, IntentRouter.wakeNames(s.keywords), llm != null)
    private val overlay = if (s.showOverlay) CapsuleOverlay(ctx, s.deviceMode) { onCapsuleTap() } else null
    private val io = Executors.newSingleThreadExecutor()

    /** 本次会话的多轮上下文（只给大模型用），会话结束清空。 */
    private val history = ArrayList<Pair<String, String>>()
    /**
     * 每次开始/结束会话或被打断都加一，用来丢弃已经过期的大模型回复和播报回调。
     * 打断发生在引擎线程，所以用原子变量，保证引擎开始聆听之前旧结果就已经作废。
     */
    private val generation = AtomicInteger()

    private enum class Ui { GREETING, LISTENING, THINKING, REPLYING }
    private var ui = Ui.GREETING
    /** true：引擎正等着我们说完回复再决定是否继续听；false：来自调试面板的文字指令。 */
    private var engineWaiting = false

    private val busListener = object : AssistantBus.Listener {
        override fun onPartial(text: String) { overlay?.partial(text) }
        override fun onLevel(db: Float) { overlay?.level(db) }
    }

    init {
        AssistantBus.add(busListener)
        val llmInfo = if (llm != null) "${s.llmModel} @ ${s.llmBaseUrl}" else "未启用（没有开或没有 key）"
        AssistantBus.log("会话：${s.deviceMode.label}，浮窗=${s.showOverlay}，连续对话 ${s.followUpSec}s，大模型 $llmInfo")
    }

    // ---------------------------------------------------------------- 引擎回调

    override fun onWake(keyword: String) {
        speaker.speak(phrases.greet) { showListening(phrases.listenHint) }
        main.post {
            generation.incrementAndGet()
            history.clear()
            ui = Ui.GREETING
            overlay?.show()
            overlay?.greeting(phrases.greet)
            AssistantBus.reply(phrases.greet)
        }
    }

    override fun onBargeIn(keyword: String) {
        generation.incrementAndGet()
        speaker.stop()
        main.post {
            engineWaiting = false
            overlay?.show()
            showListening(phrases.listenHint)
        }
    }

    override fun onCommand(text: String) {
        main.post {
            engineWaiting = true
            handle(text)
        }
    }

    override fun onSessionEnd() {
        main.post {
            generation.incrementAndGet()
            engineWaiting = false
            overlay?.hide()
        }
    }

    // ---------------------------------------------------------------- 调试面板 / adb 发来的文字指令

    fun simulate(text: String) {
        main.post {
            generation.incrementAndGet()
            history.clear()
            engineWaiting = false
            overlay?.show()
            handle(text)
        }
    }

    // ---------------------------------------------------------------- 处理一句话

    private fun handle(text: String) {
        when (val r = router.route(text)) {
            is Route.Reply -> respond(text, r.text, r.endSession)
            is Route.Online -> askLlm(text, r.query)
        }
    }

    private fun askLlm(shown: String, query: String) {
        val client = llm ?: return respond(shown, phrases.notUnderstood, false)
        ui = Ui.THINKING
        overlay?.thinking(shown, phrases.thinking)
        history += "user" to query
        val messages = listOf("system" to phrases.llmSystemPrompt(now())) + history.takeLast(MAX_HISTORY)
        val gen = generation.get()
        val t0 = System.currentTimeMillis()
        io.execute {
            val result = runCatching { client.chat(messages) }
            main.post {
                if (gen != generation.get()) return@post // 用户已经关掉、打断或开始了新会话
                result.onSuccess {
                    AssistantBus.log("大模型回复（${System.currentTimeMillis() - t0}ms）：$it")
                    history += "assistant" to it
                    respond(shown, LlmClient.plain(it), false)
                }.onFailure {
                    AssistantBus.log("大模型请求失败：${it.javaClass.simpleName} ${it.message}")
                    history.removeAt(history.lastIndex)
                    respond(shown, phrases.netError, false)
                }
            }
        }
    }

    private fun respond(query: String, answer: String, end: Boolean) {
        AssistantBus.log("回复：$answer")
        AssistantBus.reply(answer)
        ui = Ui.REPLYING
        overlay?.reply(query, answer)
        val gen = generation.get()
        speaker.speak(answer) {
            if (gen != generation.get()) return@speak
            if (end || !engineWaiting || s.followUpSec <= 0f) {
                finish()
            } else {
                showListening(phrases.followUpHint)
                engine.listenAgain(s.followUpSec)
            }
        }
    }

    private fun showListening(hint: String) {
        ui = Ui.LISTENING
        overlay?.listening(hint)
    }

    /**
     * 点胶囊：聆听时点 = 不说了，收起；助手说话或思考时点 = 打断，直接听新指令。
     */
    private fun onCapsuleTap() {
        when {
            ui == Ui.LISTENING -> close()
            ui == Ui.GREETING -> { speaker.stop(); showListening(phrases.listenHint) }
            engineWaiting -> {
                AssistantBus.log("★ 打断：点击胶囊")
                generation.incrementAndGet()
                speaker.stop()
                engineWaiting = false
                showListening(phrases.listenHint)
                engine.listenAgain(s.listenTimeoutSec)
            }
            else -> close() // 调试面板发来的文字指令，引擎没在会话里
        }
    }

    private fun close() {
        speaker.stop()
        finish()
    }

    private fun finish() {
        generation.incrementAndGet()
        engine.endSession() // 引擎没在会话里时会忽略
        engineWaiting = false
        overlay?.hide()
    }

    private fun now() = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA).format(Date())

    fun release() {
        AssistantBus.remove(busListener)
        speaker.release()
        io.shutdownNow()
        main.post { overlay?.hide() }
    }

    companion object {
        private const val MAX_HISTORY = 8
    }
}
