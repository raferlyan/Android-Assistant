package com.xiaoyan.assistant

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent

/**
 * Android 通用能力：手电筒、音量、亮度、媒体、打开应用、导航、闹钟计时器、系统设置页、搜索。
 * 只用公开 API，手机和平板车机都能跑。车控（空调、车窗、座椅）等拿到车机 SDK 后再加。
 *
 * 从后台启动 Activity 依赖「悬浮窗」权限（拥有 SYSTEM_ALERT_WINDOW 的应用不受后台启动限制）；
 * ColorOS / MIUI 另外还有一个「后台弹出界面」开关，也要打开。
 */
class DeviceActions(private val ctx: Context) {

    private val audio = ctx.getSystemService(AudioManager::class.java)

    // ---------------------------------------------------------------- 手电筒

    fun torch(on: Boolean): Boolean = try {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.first {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        cm.setTorchMode(id, on)
        true
    } catch (e: Exception) {
        AssistantBus.log("手电筒操作失败：${e.message}")
        false
    }

    // ---------------------------------------------------------------- 音量（媒体音量）

    fun volumeStep(up: Boolean) {
        val dir = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
    }

    /** 按百分比设置，最小给 1 格，否则之后的语音回复就听不见了。 */
    fun volumePercent(pct: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (pct.coerceIn(0, 100) * max + 50) / 100
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceAtLeast(1), AudioManager.FLAG_SHOW_UI)
    }

    fun mute(on: Boolean) {
        val dir = if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }

    // ---------------------------------------------------------------- 亮度（需要「修改系统设置」权限）

    fun canWriteSettings() = Settings.System.canWrite(ctx)

    fun requestWriteSettings() = start(
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")),
    )

    fun brightnessPercent(pct: Int): Boolean = setBrightness((pct.coerceIn(1, 100) * 255 + 50) / 100)

    fun brightnessStep(up: Boolean): Boolean {
        val cur = runCatching {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return setBrightness(cur + if (up) 51 else -51)
    }

    private fun setBrightness(v: Int): Boolean = try {
        val cr = ctx.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(5, 255))
        true
    } catch (e: Exception) {
        AssistantBus.log("调节亮度失败：${e.message}")
        false
    }

    // ---------------------------------------------------------------- 媒体

    /** 发媒体按键，当前正在播放（或最近播放）的应用会响应。 */
    fun mediaKey(code: Int) {
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    /** 「播放周杰伦的歌」：交给支持搜索播放的音乐应用。 */
    fun playFromSearch(q: String) = start(
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, q),
    )

    // ---------------------------------------------------------------- 应用

    /**
     * 按桌面图标名称找应用：先找完全相同的，再找包含关系里名字最短的
     * （「地图」→「高德地图」，「微信」不会匹配到「微信读书」）。
     */
    fun findApp(name: String): Pair<String, String>? {
        val key = name.lowercase().replace(" ", "")
        if (key.isEmpty()) return null
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val norm = { s: String -> s.lowercase().replace(" ", "") }
        return apps.firstOrNull { norm(it.first) == key }
            ?: apps.filter { norm(it.first).contains(key) }.minByOrNull { it.first.length }
            ?: apps.filter { it.first.length >= 2 && key.contains(norm(it.first)) }.maxByOrNull { it.first.length }
    }

    fun launch(pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return start(i)
    }

    fun home() = start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))

    // ---------------------------------------------------------------- 导航 / 闹钟 / 设置 / 拨号 / 搜索

    /** geo: 是通用协议，高德、百度、腾讯地图都会响应。 */
    fun navigate(dest: String) = start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))))

    fun timer(seconds: Int, label: String) = start(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
    )

    fun alarm(hour: Int, minute: Int) = start(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "小晏闹钟")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
    )

    /** Android 10 起普通应用不能直接开关 WiFi / 蓝牙，只能打开设置面板让用户点。 */
    fun wifiPanel() = start(
        Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS),
    )

    fun mobileDataPanel() = start(
        Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS),
    )

    fun bluetoothSettings() = start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    fun dial(num: String) = start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))

    /** 先找系统的网页搜索，没有就用浏览器打开百度。 */
    fun webSearch(q: String) =
        start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.baidu.com/s?wd=" + Uri.encode(q))))

    private fun start(i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        AssistantBus.log("没有应用能处理：${i.action}")
        false
    } catch (e: SecurityException) {
        AssistantBus.log("启动被拒绝：${i.action} ${e.message}")
        false
    }
}
