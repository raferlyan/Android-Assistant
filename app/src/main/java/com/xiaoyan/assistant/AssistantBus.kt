package com.xiaoyan.assistant

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class EngineState(val label: String) {
    STOPPED("已停止"),
    LOADING("加载模型中…"),
    IDLE("等待唤醒：说「小晏小晏」"),
    LISTENING("正在聆听…"),
    RECOGNIZING("识别中…"),
    ERROR("出错"),
}

/** 引擎线程 → UI 的事件通道。所有回调都在主线程触发。 */
object AssistantBus {
    const val TAG = "XiaoYan"

    interface Listener {
        fun onState(state: EngineState) {}
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        fun onLevel(db: Float) {}
        fun onLog(line: String) {}
        fun onStats(wakeCount: Int) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile var state = EngineState.STOPPED; private set
    @Volatile var wakeCount = 0; private set
    /** TTS 播报期间为 true，引擎丢弃麦克风数据，避免自己唤醒自己。 */
    @Volatile var speaking = false

    val logs = ArrayDeque<String>()

    fun add(l: Listener) = listeners.add(l)
    fun remove(l: Listener) = listeners.remove(l)

    fun state(s: EngineState) {
        state = s
        main.post { listeners.forEach { it.onState(s) } }
    }

    fun partial(t: String) = main.post { listeners.forEach { it.onPartial(t) } }
    fun final(t: String) = main.post { listeners.forEach { it.onFinal(t) } }
    fun level(db: Float) = main.post { listeners.forEach { it.onLevel(db) } }

    fun wake() {
        wakeCount++
        val n = wakeCount
        main.post { listeners.forEach { it.onStats(n) } }
    }

    fun resetStats() {
        wakeCount = 0
        main.post { listeners.forEach { it.onStats(0) } }
    }

    fun log(msg: String) {
        Log.i(TAG, msg)
        val line = "${fmt.format(Date())}  $msg"
        main.post {
            logs.addLast(line)
            while (logs.size > 300) logs.removeFirst()
            listeners.forEach { it.onLog(line) }
        }
    }
}
