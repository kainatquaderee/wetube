package dev.wetube.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.wetube.app.MainActivity
import dev.wetube.app.R

/**
 * Keeps the process alive — and the CPU awake — while a TV owns playback.
 *
 * Casting pauses the local player, and Media3 only runs its own media
 * notification as a foreground service while *it* has something to play. With
 * the phone's player paused there is nothing for that service to foreground, so
 * the process drops out of the foreground set; the OS then freezes it shortly
 * after the screen goes off and the cast poll loop stops, leaving the TV playing
 * with no controller and the UI stuck on its last frame.
 *
 * This service closes that gap. It is deliberately not Media3's service:
 * forcing `shouldRunInForeground` through the session does not survive Media3's
 * own notification recomputation, and a second media session just to hold a
 * notification would be a tug-of-war over the same notification id. A small,
 * dedicated foreground service plus a scoped partial wake lock is the honest
 * shape of the problem: "a cast is active, keep running until it is not."
 *
 * The lock is released in [onDestroy], which is reached on disconnect, on
 * session failure, and when the user stops the app. It is never acquired for
 * local playback.
 */
class CastKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val device = intent?.getStringExtra(EXTRA_DEVICE)
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.cast_notification_unknown_device)

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(device),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        acquireWakeLock()

        // The service is tied to one live session. If the process is killed
        // there is no session left to keep awake, so a sticky restart would only
        // resurrect a notification for a cast that is gone.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        // The session is over; the notification must not outlive it.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            // Not reference-counted: the service owns exactly one lock and
            // releases it once, regardless of how many times it is restarted
            // with a new device name.
            setReferenceCounted(false)
            // A finite timeout keeps the platform from holding a lock forever
            // if this service is ever leaked. Six hours is far longer than a
            // cast session; the normal path releases it in onDestroy.
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        val held = wakeLock ?: return
        wakeLock = null
        if (held.isHeld) held.release()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.cast_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.cast_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notification(device: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            CONTENT_INTENT_REQUEST,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.cast_notification_title, device))
            .setContentText(getString(R.string.cast_notification_text))
            .setContentIntent(open)
            // Ongoing: dismissing it would be a silent disconnect, and the
            // session is still live until the user says otherwise.
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    companion object {
        /** Distinct from Media3's own notification id so the two never swap. */
        private const val NOTIFICATION_ID = 1002
        private const val CONTENT_INTENT_REQUEST = 1002
        private const val CHANNEL_ID = "casting"
        private const val EXTRA_DEVICE = "device_name"

        private const val WAKE_LOCK_TAG = "wetube:casting"
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        /**
         * Starts or updates the keep-alive for a live cast.
         *
         * Best effort by design: if the OS refuses the foreground start (for
         * example because the app is somehow already in the background) the cast
         * still works while the process lives. Swallowing that is better than
         * crashing the screen that asked for it.
         */
        fun start(context: Context, deviceName: String) {
            val intent = Intent(context, CastKeepAliveService::class.java)
                .putExtra(EXTRA_DEVICE, deviceName)
            try {
                context.startForegroundService(intent)
            } catch (failure: RuntimeException) {
                // ForegroundServiceStartNotAllowedException is a
                // RuntimeException; nothing actionable for the user here.
            }
        }

        /** Ends the keep-alive; the wake lock is released by the service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, CastKeepAliveService::class.java))
        }
    }
}