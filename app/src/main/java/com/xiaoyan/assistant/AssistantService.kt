package com.xiaoyan.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** 前台服务承载引擎，息屏/切后台也能保持唤醒监听。 */
class AssistantService : Service() {

    private var engine: AssistantEngine? = null
    private var commands: CommandHandler? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngine()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> { // START / RELOAD
                goForeground()
                stopEngine()
                startEngine()
            }
        }
        return START_STICKY
    }

    private fun startEngine() {
        val s = AppSettings.load(this)
        AssistantBus.log("启动引擎：${s.asrMode.label}，阈值=${s.kwsThreshold}，增强=${s.kwsScore}")
        val c = CommandHandler(this, s.speakReply).also { commands = it }
        engine = AssistantEngine(this, s) { text -> c.handle(text) }.also { it.start() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XiaoYan::engine")
            .apply { acquire() }
    }

    private fun stopEngine() {
        engine?.stop(); engine = null
        commands?.release(); commands = null
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        AssistantBus.state(EngineState.STOPPED)
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "语音唤醒", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("小晏助手运行中")
            .setContentText("说「小晏小晏」「小海小海」「小章小章」唤醒")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "assistant"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, AssistantService::class.java).setAction(action)
            if (action == ACTION_STOP) ctx.startService(i) else ctx.startForegroundService(i)
        }
    }
}
