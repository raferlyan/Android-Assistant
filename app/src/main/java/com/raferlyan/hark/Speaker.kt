package com.raferlyan.hark

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统 TTS 封装：每句话带一个播完回调（在主线程执行）。
 *
 * [speak] 会同步置 [AssistantBus.speaking]，引擎在下一帧就开始丢弃麦克风数据，
 * 所以在引擎线程里调用也不会把「我在」录进去。
 */
class Speaker(ctx: Context, enabled: Boolean) {

    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, () -> Unit>()
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    init {
        if (enabled) {
            tts = TextToSpeech(ctx) { status ->
                val t = tts ?: return@TextToSpeech
                if (status == TextToSpeech.SUCCESS) {
                    val r = t.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    AssistantBus.log(if (ready) "TTS 就绪（${t.defaultEngine}）" else "系统 TTS 不支持中文，只显示文字回复")
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) = finished(id)
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            AssistantBus.log("TTS 播报出错")
                            finished(id)
                        }
                        // 被下一句 QUEUE_FLUSH 打断：不回调，新的那句会接管
                        override fun onStop(id: String?, interrupted: Boolean) { pending.remove(id) }
                    })
                } else {
                    AssistantBus.log("TTS 初始化失败：$status")
                }
            }
        }
    }

    private fun finished(id: String?) {
        AssistantBus.speaking = false
        pending.remove(id)?.let { main.post(it) }
    }

    fun speak(text: String, onDone: () -> Unit) {
        val t = tts
        if (!ready || t == null) {
            // 不播报时给用户留点时间看浮窗上的字
            main.postDelayed(onDone, (600L + text.length * 120L).coerceAtMost(4000L))
            return
        }
        val id = "u-${System.nanoTime()}"
        pending[id] = onDone
        AssistantBus.speaking = true
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finished(id)
    }

    /** 打断当前播报，丢掉所有回调。 */
    fun stop() {
        pending.clear()
        main.removeCallbacksAndMessages(null)
        tts?.stop()
        AssistantBus.speaking = false
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
