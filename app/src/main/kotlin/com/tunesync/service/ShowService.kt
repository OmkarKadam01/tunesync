package com.tunesync.service

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
import com.tunesync.MainActivity
import com.tunesync.R

/**
 * Where the running show lives while the app is not on screen.
 *
 * The work itself stays in the view model; this exists so the process is not
 * throttled or killed when the user switches away, and so there is always a
 * stop control in the shade. Without it the only safe behaviour was to stop the
 * show on `onPause`, because leaving a torch strobing in a pocket is worse than
 * cutting it short.
 */
class ShowService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ShowSession.requestStop()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val listening = intent?.getBooleanExtra(EXTRA_LISTENING, false) ?: false
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)

        createChannel()
        val notification = buildNotification(title, listening)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 14 requires the type to match what the service actually does,
            // and microphone capture is not media playback.
            val type = if (listening) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Belt and braces: if the system tears us down, nothing should be left lit.
        ShowSession.requestStop()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        ShowSession.requestStop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(title: String, listening: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ShowService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(
                if (listening) {
                    getString(R.string.notif_listening)
                } else {
                    getString(R.string.notif_running)
                },
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.stop), stop).build(),
            )
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            // Low: this is a control surface, not something to interrupt anyone with.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "tunesync_show"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.tunesync.action.STOP"
        private const val EXTRA_LISTENING = "listening"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, listening: Boolean, title: String) {
            val intent = Intent(context, ShowService::class.java)
                .putExtra(EXTRA_LISTENING, listening)
                .putExtra(EXTRA_TITLE, title)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ShowService::class.java))
        }
    }
}

/**
 * The one link between the notification's stop action and whatever is running.
 *
 * A bound service would be the tidier shape, but the show is owned by the view
 * model and binding only to deliver a single "stop" would be more machinery
 * than the problem needs.
 */
object ShowSession {
    @Volatile
    private var stopHandler: (() -> Unit)? = null

    fun register(handler: () -> Unit) {
        stopHandler = handler
    }

    fun unregister() {
        stopHandler = null
    }

    fun requestStop() {
        stopHandler?.invoke()
    }
}
