package com.xiaoyan.assistant

/**
 * 回复话术。车机和手机分两套：
 * - 车机：用户在开车，句子短、先说「好的」再报结果，不寒暄；
 * - 手机：口语化一些，语气更随意。
 *
 * 播报内容和浮窗显示的是同一句话，所以这里不放表情和符号。
 */
class Phrases(val mode: DeviceMode) {

    private fun pick(car: String, phone: String) = if (mode == DeviceMode.CAR) car else phone

    val greet = "我在"
    val listenHint get() = pick("请说", "请说，我在听")
    val followUpHint get() = pick("还有什么需要", "还需要什么，直接说就行")
    val thinking = "思考中…"

    val bye get() = pick("好的", "好的，有需要再叫我")
    val thanks get() = pick("不客气", "不客气，随时叫我")
    val cancel = "好的"

    fun torch(on: Boolean) = if (on) pick("好的，已打开手电筒", "手电筒打开啦") else pick("好的，已关闭手电筒", "手电筒关掉啦")
    val torchFail get() = pick("手电筒暂时无法使用", "手电筒好像用不了")

    val volumeUp get() = pick("好的，音量已调高", "音量调大了")
    val volumeDown get() = pick("好的，音量已调低", "音量调小了")
    val volumeMax get() = pick("好的，音量已调到最大", "音量已经调到最大了")
    val volumeMin get() = pick("好的，音量已调到最小", "音量已经调到最小了")
    fun volumeSet(pct: Int) = pick("好的，音量已调到百分之$pct", "音量调到百分之${pct}了")
    val mute get() = pick("好的，已静音", "已经静音了")
    val unmute get() = pick("好的，已取消静音", "已经取消静音了")

    val brightnessUp get() = pick("好的，屏幕已调亮", "屏幕调亮了")
    val brightnessDown get() = pick("好的，屏幕已调暗", "屏幕调暗了")
    val brightnessMax get() = pick("好的，亮度已调到最高", "亮度已经调到最高了")
    val brightnessMin get() = pick("好的，亮度已调到最低", "亮度已经调到最低了")
    fun brightnessSet(pct: Int) = pick("好的，亮度已调到百分之$pct", "亮度调到百分之${pct}了")
    val needWriteSettings get() = pick(
        "请先允许小晏修改系统设置",
        "需要你先允许我修改系统设置，设置页面已经打开了",
    )

    val mediaPlay get() = pick("好的，开始播放", "开始播放啦")
    val mediaPause get() = pick("好的，已暂停", "已经暂停了")
    val mediaNext get() = pick("好的，下一首", "切到下一首了")
    val mediaPrev get() = pick("好的，上一首", "切回上一首了")
    fun playSearch(q: String) = pick("好的，为你播放$q", "马上为你播放$q")

    fun openApp(name: String) = pick("好的，已打开$name", "${name}打开啦")
    fun appNotFound(name: String) = pick("没有找到$name", "手机上好像没有装$name")
    fun cannotClose(name: String) = pick("暂不支持关闭$name", "我还没办法帮你关闭$name")
    val home get() = pick("好的", "回到桌面了")

    fun navigate(dest: String) = pick("好的，开始导航到$dest", "正在为你规划去${dest}的路线")
    val noMapApp get() = pick("未找到可用的导航应用", "没找到地图应用，先装一个高德或百度地图吧")

    fun timer(desc: String) = pick("好的，已设置${desc}倒计时", "好嘞，${desc}的计时器已经开始了")
    fun alarm(desc: String) = pick("好的，已设置${desc}的闹钟", "已经帮你定好${desc}的闹钟")

    fun settingsPage(name: String) = pick(
        "已打开${name}设置，请手动切换",
        "${name}设置已经打开了，系统不让我直接开关，你点一下就好",
    )
    fun dial(num: String) = pick("好的，已为你打开拨号，号码$num", "拨号界面打开了，号码是$num")
    val dialNeedNumber get() = pick("请说出要拨打的号码", "按联系人拨号还不支持，直接说号码试试")

    fun time(s: String) = "现在是$s"
    fun date(s: String) = "今天是$s"
    val intro get() = pick("我是小晏，你的车载语音助手", "我是小晏，你的手机语音助手")

    fun webSearch(q: String) = pick("已为你搜索$q", "帮你搜了一下$q，看看屏幕吧")
    val notUnderstood get() = pick("抱歉，这个我还不会", "这个我还不太会，换个说法试试")
    val netError get() = pick("网络异常，请稍后再试", "网络好像不太好，等会儿再问我吧")
    val actionFail get() = pick("操作失败", "出了点问题，没办法完成")

    /** 大模型的系统提示词，同样分车机/手机两种口吻。 */
    fun llmSystemPrompt(now: String) = pick(
        "你是车载语音助手「小晏」，运行在平板车机上。用户正在驾车，回答必须简短直接，" +
            "一般一到两句话、不超过60个字，口语化，不要反问。",
        "你是手机语音助手「小晏」。回答口语化、亲切自然，像朋友聊天，一般不超过100个字。",
    ) + "回答会被直接转成语音播报，所以不要使用 Markdown、列表、编号、表情符号和链接。" +
        "你无法联网获取实时信息（天气、路况、新闻、股价、比分等），遇到这类问题直接说明，" +
        "并建议用户说「搜索」加关键词。当前时间：$now。"
}
