package com.liuchong.tuner.audio

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
import androidx.core.app.NotificationCompat
import com.liuchong.tuner.MainActivity

/**
 * 节拍器前台保活 Service（spec-audio §2 / android.md 规则 4）：
 * 仅保活与通知栏，不含音频逻辑；通知显示当前 BPM，带停止按钮。
 */
class MetronomeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                onStopRequested?.invoke()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val bpm = intent?.getDoubleExtra(EXTRA_BPM, 120.0) ?: 120.0
                startForegroundWithNotification(bpm)
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification(bpm: Double) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "节拍器",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = buildNotification(bpm)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(bpm: Double): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MetronomeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("节拍器播放中")
            .setContentText("BPM ${bpm.toInt()}")
            .setContentIntent(contentIntent)
            .addAction(0, "停止", stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.liuchong.tuner.metronome.START"
        const val ACTION_STOP = "com.liuchong.tuner.metronome.STOP"
        const val EXTRA_BPM = "bpm"
        private const val CHANNEL_ID = "metronome"
        private const val NOTIFICATION_ID = 42

        /** 通知栏停止按钮回调（由 ViewModel 注册）。 */
        @Volatile
        var onStopRequested: (() -> Unit)? = null

        fun start(context: Context, bpm: Double) {
            val intent = Intent(context, MetronomeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BPM, bpm)
            context.startForegroundService(intent)
        }

        /** 更新通知中的 BPM（播放中变速）。 */
        fun updateBpm(context: Context, bpm: Double) = start(context, bpm)

        fun stop(context: Context) {
            context.stopService(Intent(context, MetronomeService::class.java))
        }
    }
}
