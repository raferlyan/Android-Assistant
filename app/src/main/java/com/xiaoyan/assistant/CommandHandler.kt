package com.xiaoyan.assistant

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 识别出文字之后做什么。这里只放几个本地指令做演示，接大模型/业务逻辑就改 [reply]。 */
class CommandHandler(private val ctx: Context, private val speakReply: Boolean) {

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    init {
        if (speakReply) {
            tts = TextToSpeech(ctx) { status ->
                val t = tts ?: return@TextToSpeech
                if (status == TextToSpeech.SUCCESS) {
                    val r = t.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    AssistantBus.log(if (ttsReady) "TTS 就绪（${t.defaultEngine}）" else "系统 TTS 不支持中文，只显示文字回复")
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {
                            AssistantBus.speaking = true
                            AssistantBus.log("TTS 开始播报（麦克风静音）")
                        }
                        override fun onDone(id: String?) {
                            AssistantBus.speaking = false
                            AssistantBus.log("TTS 播报结束，恢复监听")
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            AssistantBus.speaking = false
                            AssistantBus.log("TTS 播报出错，恢复监听")
                        }
                    })
                } else {
                    AssistantBus.log("TTS 初始化失败：$status")
                }
            }
        }
    }

    fun handle(text: String) {
        val answer = reply(text)
        AssistantBus.log("回复：$answer")
        if (ttsReady) {
            AssistantBus.speaking = true
            tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "reply-${System.nanoTime()}")
        }
    }

    private fun reply(text: String): String {
        val t = text.replace(Regex("[，。？！、,.?!\\s]"), "")
        return when {
            "手电" in t && ("关" in t) -> torch(false)
            "手电" in t && ("开" in t) -> torch(true)
            "音量" in t && ("大" in t || "高" in t) -> volume(AudioManager.ADJUST_RAISE)
            "音量" in t && ("小" in t || "低" in t) -> volume(AudioManager.ADJUST_LOWER)
            "几点" in t || "时间" in t ->
                "现在是" + SimpleDateFormat("H点m分", Locale.CHINA).format(Date())
            "几号" in t || "日期" in t || "星期" in t ->
                "今天是" + SimpleDateFormat("M月d日EEEE", Locale.CHINA).format(Date())
            "你是谁" in t || "名字" in t -> "我是小晏，你的离线语音助手"
            else -> "你说的是：$text"
        }
    }

    private fun torch(on: Boolean): String = try {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.first {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        cm.setTorchMode(id, on)
        if (on) "手电筒已打开" else "手电筒已关闭"
    } catch (e: Exception) {
        "手电筒操作失败：${e.message}"
    }

    private fun volume(dir: Int): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
        return if (dir == AudioManager.ADJUST_RAISE) "音量已调大" else "音量已调小"
    }

    fun release() {
        tts?.shutdown()
        tts = null
        AssistantBus.speaking = false
    }
}
