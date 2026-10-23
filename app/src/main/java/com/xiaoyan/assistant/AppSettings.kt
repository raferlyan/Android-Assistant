package com.xiaoyan.assistant

import android.content.Context

/** 识别模式：普通话流式（支持热词）/ 方言（TeleSpeech）/ 粤语及多语种（SenseVoice）。 */
enum class AsrMode(val label: String) {
    MANDARIN_STREAMING("普通话·流式（支持热词）"),
    DIALECT_TELESPEECH("方言·TeleSpeech（川/粤/闽/吴/湘等）"),
    SENSEVOICE("粤语/多语种·SenseVoice"),
}

/**
 * 所有可调参数。保存在 SharedPreferences，修改后由 Service 重新加载引擎。
 */
data class AppSettings(
    /** 每行一个唤醒词：`小晏小晏 :2.0 #0.20`，也可以直接写 token：`x iǎo y àn x iǎo y àn @小晏小晏`。 */
    val keywords: String = DEFAULT_KEYWORDS,
    /** 全局唤醒阈值，越小越容易唤醒（误唤醒也越多）。 */
    val kwsThreshold: Float = 0.25f,
    /** 全局唤醒词增强分数，越大越容易唤醒。 */
    val kwsScore: Float = 1.5f,
    /** 唤醒后跟随多少个 blank 帧才确认触发，越大越不容易被前缀误触发。 */
    val kwsTrailingBlanks: Int = 1,
    /** 只做唤醒统计、不进入识别，用于调唤醒率/误唤醒。 */
    val kwsTestMode: Boolean = false,

    val asrMode: AsrMode = AsrMode.MANDARIN_STREAMING,
    /** 每行一个热词，可带分数：`手电筒 :2.5`。仅普通话流式模式生效。 */
    val hotwords: String = DEFAULT_HOTWORDS,
    val hotwordsScore: Float = 1.5f,
    /** 识别结果纠错，每行 `错词=>正确词`，所有模式都生效（方言模型不支持热词时靠它兜底）。 */
    val corrections: String = DEFAULT_CORRECTIONS,
    /** SenseVoice 语种：auto / zh / yue / en / ja / ko */
    val senseVoiceLang: String = "auto",

    /** 方言/SenseVoice 模式的 VAD 阈值，越小越灵敏。远场、小声、鼻音开头（如粤语「唔该」）需要调低。 */
    val vadThreshold: Float = 0.3f,
    /** 唤醒后多久没开口就退出（秒）。 */
    val listenTimeoutSec: Float = 6f,
    /** 说完后静音多久判定一句话结束（秒）。 */
    val endSilenceSec: Float = 0.8f,
    val numThreads: Int = 2,
    val beepOnWake: Boolean = true,
    val speakReply: Boolean = true,
    val saveAudio: Boolean = true,
) {
    fun save(ctx: Context) {
        prefs(ctx).edit()
            .putString("keywords", keywords)
            .putFloat("kwsThreshold", kwsThreshold)
            .putFloat("kwsScore", kwsScore)
            .putInt("kwsTrailingBlanks", kwsTrailingBlanks)
            .putBoolean("kwsTestMode", kwsTestMode)
            .putString("asrMode", asrMode.name)
            .putString("hotwords", hotwords)
            .putFloat("hotwordsScore", hotwordsScore)
            .putString("corrections", corrections)
            .putString("senseVoiceLang", senseVoiceLang)
            .putFloat("vadThreshold", vadThreshold)
            .putFloat("listenTimeoutSec", listenTimeoutSec)
            .putFloat("endSilenceSec", endSilenceSec)
            .putInt("numThreads", numThreads)
            .putBoolean("beepOnWake", beepOnWake)
            .putBoolean("speakReply", speakReply)
            .putBoolean("saveAudio", saveAudio)
            .apply()
    }

    companion object {
        const val DEFAULT_KEYWORDS = "小晏小晏 :2.0 #0.20\n小海小海 :2.0 #0.20\n小章小章 :2.0 #0.20"
        const val DEFAULT_HOTWORDS = "打开手电 :2.0\n关闭手电 :2.0\n音量调大\n音量调小\n现在几点"
        const val DEFAULT_CORRECTIONS = "小燕=>小晏\n小雁=>小晏\n小艳=>小晏\n小严=>小晏\n大开=>打开"

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun load(ctx: Context): AppSettings {
            val p = prefs(ctx)
            val d = AppSettings()
            return AppSettings(
                keywords = p.getString("keywords", d.keywords)!!,
                kwsThreshold = p.getFloat("kwsThreshold", d.kwsThreshold),
                kwsScore = p.getFloat("kwsScore", d.kwsScore),
                kwsTrailingBlanks = p.getInt("kwsTrailingBlanks", d.kwsTrailingBlanks),
                kwsTestMode = p.getBoolean("kwsTestMode", d.kwsTestMode),
                asrMode = runCatching { AsrMode.valueOf(p.getString("asrMode", d.asrMode.name)!!) }
                    .getOrDefault(d.asrMode),
                hotwords = p.getString("hotwords", d.hotwords)!!,
                hotwordsScore = p.getFloat("hotwordsScore", d.hotwordsScore),
                corrections = p.getString("corrections", d.corrections)!!,
                senseVoiceLang = p.getString("senseVoiceLang", d.senseVoiceLang)!!,
                vadThreshold = p.getFloat("vadThreshold", d.vadThreshold),
                listenTimeoutSec = p.getFloat("listenTimeoutSec", d.listenTimeoutSec),
                endSilenceSec = p.getFloat("endSilenceSec", d.endSilenceSec),
                numThreads = p.getInt("numThreads", d.numThreads),
                beepOnWake = p.getBoolean("beepOnWake", d.beepOnWake),
                speakReply = p.getBoolean("speakReply", d.speakReply),
                saveAudio = p.getBoolean("saveAudio", d.saveAudio),
            )
        }
    }
}
