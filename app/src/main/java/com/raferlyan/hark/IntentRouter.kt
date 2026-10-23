package com.raferlyan.hark

import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 路由结果：要么本地已经处理完、直接回复；要么交给在线大模型。 */
sealed interface Route {
    data class Reply(val text: String, val endSession: Boolean = false) : Route
    data class Online(val query: String) : Route
}

/**
 * 本地意图识别：正则模板 + 槽位抽取（数字、时间、应用名、目的地）。
 * 规则自上而下匹配，命中即执行；都没命中再交给大模型（没配置大模型就回复「不会」）。
 *
 * 规则顺序有讲究：具体的在前（「打开手电筒」），泛化的在后（「打开 X」→ 找应用）。
 */
class IntentRouter(
    private val act: DeviceActions,
    private val p: Phrases,
    private val wakeNames: List<String>,
    private val onlineAvailable: Boolean,
) {

    fun route(raw: String): Route {
        val t = normalize(raw)
        AssistantBus.log("意图匹配：「$t」")
        return match(t) ?: when {
            onlineAvailable -> Route.Online(raw.trim())
            else -> Route.Reply(p.notUnderstood)
        }
    }

    private fun normalize(raw: String): String {
        var t = raw.replace(Regex("[，。？！、,.?!；;：:\\s\"“”]"), "").lowercase()
        // 多轮对话里用户可能再喊一次唤醒词
        var changed = true
        while (changed) {
            changed = false
            for (w in wakeNames + POLITE) {
                if (w.isNotEmpty() && t.startsWith(w)) { t = t.removePrefix(w); changed = true }
            }
        }
        return t.replace("声音", "音量").replace("一半", "50").replace("无线网络", "wifi")
            .replace("无线网", "wifi").replace("wi-fi", "wifi")
    }

    private fun reply(text: String, end: Boolean = false) = Route.Reply(text, end)
    private fun has(t: String, vararg words: String) = words.any { it in t }

    private fun match(t: String): Route? {
        if (t.isEmpty()) return reply(p.greet)
        if (t in EXIT) return reply(p.bye, end = true)
        if (t in THANKS) return reply(p.thanks, end = true)

        // ---- 媒体暂停要先于否定词判断（「别放了」是暂停，不是取消）
        if (Regex("^(暂停|停止播放|别放了|不要放了|别唱了|停一下)").containsMatchIn(t) || t == "暂停播放") {
            act.mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); return reply(p.mediaPause)
        }
        if (Regex("^(不要|别|不用)").containsMatchIn(t)) return reply(p.cancel)

        // ---- 手电筒
        if (has(t, "手电", "闪光灯")) {
            val on = !has(t, "关")
            return reply(if (act.torch(on)) p.torch(on) else p.torchFail)
        }

        // ---- 音量
        if ("取消静音" in t || "解除静音" in t) { act.mute(false); return reply(p.unmute) }
        if ("静音" in t) { act.mute(true); return reply(p.mute) }
        if ("音量" in t || has(t, "大声", "小声", "太吵")) {
            percent(t)?.let { act.volumePercent(it); return reply(p.volumeSet(it.coerceIn(0, 100))) }
            return when {
                has(t, "最大", "最高") -> { act.volumePercent(100); reply(p.volumeMax) }
                has(t, "最小", "最低") -> { act.volumePercent(1); reply(p.volumeMin) }
                has(t, "小", "低", "减", "降", "太吵") -> { act.volumeStep(false); reply(p.volumeDown) }
                else -> { act.volumeStep(true); reply(p.volumeUp) }
            }
        }

        // ---- 亮度
        if ("亮度" in t || has(t, "太暗", "太亮", "刺眼", "屏幕调亮", "屏幕调暗", "调亮", "调暗")) {
            if (!act.canWriteSettings()) { act.requestWriteSettings(); return reply(p.needWriteSettings) }
            val pct = percent(t)
            val (ok, msg) = when {
                pct != null -> act.brightnessPercent(pct) to p.brightnessSet(pct.coerceIn(1, 100))
                has(t, "最大", "最高", "最亮") -> act.brightnessPercent(100) to p.brightnessMax
                has(t, "最小", "最低", "最暗") -> act.brightnessPercent(1) to p.brightnessMin
                has(t, "暗", "低", "小", "太亮", "刺眼", "降") -> act.brightnessStep(false) to p.brightnessDown
                else -> act.brightnessStep(true) to p.brightnessUp
            }
            return reply(if (ok) msg else p.actionFail)
        }

        // ---- 媒体
        if (has(t, "下一首", "下一曲", "切歌", "换一首", "换首歌")) {
            act.mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); return reply(p.mediaNext)
        }
        if (has(t, "上一首", "上一曲")) { act.mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); return reply(p.mediaPrev) }
        if (t in PLAY_GENERIC || has(t, "继续播放", "播放音乐", "来点音乐", "放点音乐", "听歌", "放首歌", "放歌")) {
            act.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); return reply(p.mediaPlay)
        }
        Regex("^(播放|我想听|我要听|来一首|放一首|来首|听一下)(.+)$").find(t)?.let {
            val q = it.groupValues[2].removeSuffix("吧")
            if (act.playFromSearch(q)) return reply(p.playSearch(q))
            act.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); return reply(p.mediaPlay)
        }

        // ---- 车控：先占位，接入车机 SDK 后在这里分发
        CAR_CONTROL.firstOrNull { it in t }?.let { return reply(carControlTodo(it)) }

        // ---- 系统开关：Android 10+ 不允许直接切换，打开设置面板
        if ("wifi" in t || "wlan" in t) { return reply(if (act.wifiPanel()) p.settingsPage("WiFi") else p.actionFail) }
        if ("移动数据" in t || "数据流量" in t) {
            return reply(if (act.mobileDataPanel()) p.settingsPage("移动数据") else p.actionFail)
        }
        if ("蓝牙" in t) return reply(if (act.bluetoothSettings()) p.settingsPage("蓝牙") else p.actionFail)
        if (t in HOME) return reply(if (act.home()) p.home else p.actionFail)

        // ---- 计时器：「定一个五分钟的计时器」「十分钟后提醒我」
        if (has(t, "计时", "定时", "后提醒我", "后叫我")) {
            Regex("($NUM|半)个?(小时|分钟|分|秒钟|秒)").find(t)?.let { m ->
                val n = if (m.groupValues[1] == "半") 0.5 else ChineseNumber.parse(m.groupValues[1])?.toDouble()
                if (n != null) {
                    val unit = when (m.groupValues[2]) { "小时" -> 3600; "秒", "秒钟" -> 1; else -> 60 }
                    val desc = m.value
                    return reply(if (act.timer((n * unit).toInt(), desc)) p.timer(desc) else p.actionFail)
                }
            }
        }

        // ---- 闹钟：「明天早上七点半叫我」「定个八点的闹钟」
        if (has(t, "闹钟", "叫我起床", "叫醒我") || Regex("点.*叫我").containsMatchIn(t)) {
            Regex("(凌晨|早上|上午|中午|下午|晚上|明早|明天早上|明天)?($NUM)点(半|($NUM)分?)?").find(t)?.let { m ->
                var h = ChineseNumber.parse(m.groupValues[2]) ?: return@let
                val min = if (m.groupValues[3] == "半") 30 else m.groupValues[4].takeIf { it.isNotEmpty() }
                    ?.let { ChineseNumber.parse(it) } ?: 0
                val period = m.groupValues[1]
                if ((period == "下午" || period == "晚上") && h < 12) h += 12
                if (period == "中午" && h < 5) h += 12
                if (h in 0..23 && min in 0..59) {
                    return reply(if (act.alarm(h, min)) p.alarm(m.value) else p.actionFail)
                }
            }
        }

        // ---- 导航
        val nav = Regex("^(导航到|导航去|导航至|导航|带我去|我要去|我想去)(.+)$").find(t)?.groupValues?.get(2)
            ?: Regex("^(去|到)?(.+)怎么走$").find(t)?.groupValues?.get(2)
        if (nav != null) return reply(if (act.navigate(nav)) p.navigate(nav) else p.noMapApp)

        // ---- 拨号
        Regex("^(打电话给|打电话|拨打|拨号|呼叫|打给)(.*)$").find(t)?.let { m ->
            val num = m.groupValues[2].map { DIAL_DIGITS[it] ?: it }.joinToString("")
            if (!num.matches(Regex("[0-9]{3,}"))) return reply(p.dialNeedNumber)
            return reply(if (act.dial(num)) p.dial(num) else p.actionFail)
        }

        // ---- 时间 / 日期 / 自我介绍
        if (Regex("几点了|几点$|现在几点|什么时间了|现在时间|^报时").containsMatchIn(t)) {
            return reply(p.time(SimpleDateFormat("H点m分", Locale.CHINA).format(Date())))
        }
        if (Regex("几号|日期|星期几|礼拜几|周几").containsMatchIn(t) && !has(t, "明天", "后天", "昨天", "下周", "上周")) {
            return reply(p.date(SimpleDateFormat("M月d日EEEE", Locale.CHINA).format(Date())))
        }
        if (has(t, "你是谁", "你叫什么", "你的名字", "介绍一下你自己", "自我介绍")) return reply(p.intro)

        // ---- 实时信息：大模型答不了，直接上网搜
        if (has(t, *REALTIME)) {
            val q = SEARCH_PREFIX.find(t)?.groupValues?.get(2) ?: t
            return reply(if (act.webSearch(q)) p.webSearch(q) else p.actionFail)
        }
        // ---- 明确说「搜索 X」：有大模型就让大模型回答，没有就打开浏览器
        SEARCH_PREFIX.find(t)?.let {
            val q = it.groupValues[2]
            if (onlineAvailable) return Route.Online(q)
            return reply(if (act.webSearch(q)) p.webSearch(q) else p.actionFail)
        }

        // ---- 关闭 / 打开应用（放最后：前面的具体规则都没命中才当成应用名）
        Regex("^(关闭|关掉|退出)(.+)$").find(t)?.let { return reply(p.cannotClose(appName(it.groupValues[2]))) }
        Regex("^(打开|启动|开启|运行|进入)(.+)$").find(t)?.let {
            val name = appName(it.groupValues[2])
            val app = act.findApp(name) ?: return reply(p.appNotFound(name))
            AssistantBus.log("打开应用：${app.first}（${app.second}）")
            return reply(if (act.launch(app.second)) p.openApp(app.first) else p.actionFail)
        }
        return null
    }

    private fun carControlTodo(what: String) = if (p.mode == DeviceMode.CAR) {
        "车辆控制正在接入，暂时无法控制$what"
    } else {
        "我现在只能控制手机，还不能控制$what"
    }

    private fun appName(s: String) = s.replace(Regex("(一下|吧|啊|呀|应用|程序|软件|app)+$"), "")

    /** 「调到五十」「调到百分之八十」「音量30」→ 数值；不会把「调大一点」的「一」当数字。 */
    private fun percent(t: String): Int? =
        Regex("(到|为|成|音量|亮度)(百分之)?($NUM)").find(t)?.let { ChineseNumber.parse(it.groupValues[3]) }

    companion object {
        const val NUM = ChineseNumber.PATTERN

        private val POLITE = listOf("请", "帮我", "帮忙", "给我", "麻烦你", "麻烦")
        private val EXIT = setOf(
            "退出", "再见", "拜拜", "没事了", "没事", "不用了", "退下", "退下吧", "关闭助手", "好了", "就这样",
            "算了", "取消", "没有了", "没了",
        )
        private val THANKS = setOf("谢谢", "谢谢你", "好的谢谢", "谢啦", "多谢")
        private val HOME = setOf("回到桌面", "返回桌面", "回桌面", "回到主页", "返回主页", "回主页")
        private val PLAY_GENERIC = setOf("播放", "继续", "开始播放", "放音乐", "放歌")
        private val CAR_CONTROL = listOf(
            "空调", "车窗", "天窗", "座椅", "方向盘加热", "雨刮", "后视镜", "氛围灯", "大灯", "车灯",
            "后备箱", "尾门", "除雾", "遮阳帘", "香氛",
        )
        private val REALTIME = arrayOf("天气", "气温", "下雨", "新闻", "热搜", "股价", "比分", "路况", "限行", "油价")
        private val SEARCH_PREFIX = Regex("^(搜索|搜一下|搜一搜|搜搜|查一下|查查|查询|百度一下|百度|帮我查|帮我搜|上网查)(.+)$")
        private val DIAL_DIGITS = mapOf(
            '零' to '0', '幺' to '1', '一' to '1', '二' to '2', '两' to '2', '三' to '3', '四' to '4',
            '五' to '5', '六' to '6', '七' to '7', '八' to '8', '九' to '9',
        )

        /** 从唤醒词配置里取出可读的名字（「小晏小晏 :2.0」→ 小晏小晏；「x iǎo … @小晏同学」→ 小晏同学）。 */
        fun wakeNames(keywords: String): List<String> = keywords.lines().mapNotNull { line ->
            val l = line.trim()
            val name = if ('@' in l) l.substringAfter('@') else l.split(' ', ':', '#').first()
            name.trim().takeIf { n -> n.any { it.code > 0x4E00 } }
        }
    }
}

/** 中文数字 → Int，支持「五十」「十五」「一百」「两」「30」，不支持「万」。 */
object ChineseNumber {
    const val PATTERN = "[0-9零〇一二两三四五六七八九十百]+"
    private val DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    fun parse(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        var total = 0
        var cur = 0
        for (c in s) {
            when (c) {
                in DIGITS -> cur = DIGITS.getValue(c)
                '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
                '百' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0 }
                else -> return null
            }
        }
        return if (s.isEmpty()) null else total + cur
    }
}
