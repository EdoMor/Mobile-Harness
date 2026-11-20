package com.jarves.mh.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.jarves.mh.R
import com.jarves.mh.ui.AppStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android closes its "Pair device with pairing code" dialog as soon as you leave Settings,
 * and the code only works while it is open. This notification takes the code from the
 * notification shade, so the dialog stays on screen while you type it.
 */
object WirelessAdbPairingNotification {
    private const val CHANNEL_ID = "wireless_adb"
    private const val NOTIFICATION_ID = 0x4ADB
    internal const val ACTION_SUBMIT_CODE = "com.jarves.mh.action.WIRELESS_ADB_PAIRING_CODE"
    internal const val KEY_CODE = "pairing_code"

    fun showCodePrompt(context: Context) {
        ensureChannel(context)
        val text = AppStrings.get(context, R.string.adb_notification_text)
        post(
            context,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(AppStrings.get(context, R.string.adb_notification_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .addAction(codeAction(context)),
        )
    }

    internal fun showWorking(context: Context) {
        ensureChannel(context)
        post(
            context,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(AppStrings.get(context, R.string.adb_title))
                .setContentText(AppStrings.get(context, R.string.adb_notification_pairing))
                .setProgress(0, 0, true)
                .setOnlyAlertOnce(true)
                .setOngoing(true),
        )
    }

    internal fun showResult(context: Context, result: WirelessAdbResult) {
        if (!result.success) {
            // Keep the code field so a mistyped code can be entered again.
            ensureChannel(context)
            post(
                context,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(AppStrings.get(context, R.string.adb_notification_title))
                    .setContentText(result.message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(result.message))
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .addAction(codeAction(context)),
            )
            return
        }
        post(
            context,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(AppStrings.get(context, R.string.adb_title))
                .setContentText(result.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(result.message))
                .setTimeoutAfter(15_000L)
                .setAutoCancel(true),
        )
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun codeAction(context: Context): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_CODE)
            .setLabel(AppStrings.get(context, R.string.adb_notification_input_label))
            .build()
        val submit = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WirelessAdbPairingReceiver::class.java).setAction(ACTION_SUBMIT_CODE),
            // RemoteInput fills the intent in, so it must stay mutable; it is explicit to our receiver.
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            AppStrings.get(context, R.string.adb_notification_action),
            submit,
        ).addRemoteInput(remoteInput).setAllowGeneratedReplies(false).build()
    }

    private fun post(context: Context, builder: NotificationCompat.Builder) {
        // POST_NOTIFICATIONS may be denied; the manual pairing fields in Settings still work.
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                AppStrings.get(context, R.string.adb_title),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { setShowBadge(false) },
        )
    }
}

/** Receives the code typed into [WirelessAdbPairingNotification] and pairs this phone. */
class WirelessAdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WirelessAdbPairingNotification.ACTION_SUBMIT_CODE) return
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(WirelessAdbPairingNotification.KEY_CODE)
            ?.toString()
            .orEmpty()
        val app = context.applicationContext
        val pending = goAsync()
        WirelessAdbPairingNotification.showWorking(app)
        scope.launch {
            try {
                val result = WirelessAdbManager.get(app).pair(code)
                WirelessAdbPairingNotification.showResult(app, result)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
