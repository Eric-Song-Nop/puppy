package org.pocketlab.puppy.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.pocketlab.puppy.MainActivity
import org.pocketlab.puppy.PuppyApp
import org.pocketlab.puppy.R

/**
 * Keeps the process in the foreground while sessions are open (design §6.4).
 * It holds no session state itself; [SessionManager] does. It stops when
 * the last session closes. A wake lock is only taken when the user asks.
 */
class SessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = (application as PuppyApp).sessions
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                manager.closeAll()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_WAKE_LOCK -> toggleWakeLock()
        }
        val count = manager.sessions.value.size
        if (count == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(count))
        return START_NOT_STICKY
    }

    private fun toggleWakeLock() {
        val held = wakeLock
        if (held != null) {
            if (held.isHeld) held.release()
            wakeLock = null
        } else {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "puppy:sessions").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MAX_MS)
            }
        }
    }

    private fun buildNotification(count: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, SessionService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE,
        )
        val locked = wakeLock?.isHeld == true
        val text = resources.getQuantityString(R.plurals.sessions_open, count, count) +
            if (locked) " · " + getString(R.string.wake_lock_held) else ""
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, getString(if (locked) R.string.release_wake_lock else R.string.acquire_wake_lock), action(ACTION_TOGGLE_WAKE_LOCK, 1)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_all_sessions), action(ACTION_STOP_ALL, 2)).build())
            .build()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "sessions"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP_ALL = "org.pocketlab.puppy.STOP_ALL"
        const val ACTION_TOGGLE_WAKE_LOCK = "org.pocketlab.puppy.TOGGLE_WAKE_LOCK"
        /** Even a user-requested wake lock expires so it can't drain the battery forever. */
        private const val WAKE_LOCK_MAX_MS = 4 * 60 * 60 * 1000L

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_sessions), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }

        /** Starts, refreshes or stops the service to match the session count. */
        fun update(context: Context, count: Int) {
            val intent = Intent(context, SessionService::class.java)
            if (count > 0) {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
