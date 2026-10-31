package ke.elgonlabs.barberbeyond

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Booking alerts are opt-in. Only generic text is shown on the phone's lock screen.
 * User tokens are encrypted by the Android Keystore; no service-role token is used.
 * Foreground checks run about every 45s; Android WorkManager may check in the background
 * every 15 minutes or later, depending on connectivity and system battery restrictions.
 * This is not FCM instant push when the application is closed.
 */
internal object BookingAlertStore {
    private const val PREFS = "bb_booking_notifications"
    private const val KEY_ALIAS = "bb_booking_notifications_aes_v1"
    private const val WORK = "bb_booking_background_check"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean("enabled", false)

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun saveSession(context: Context, session: BookingSession?) {
        if (session == null || !isEnabled(context)) {
            prefs(context).edit().remove("encrypted_session").remove("session_iv").apply()
            return
        }
        val payload = JSONObject().put("access", session.access)
            .put("refresh", session.refresh)
            .put("expiresAt", session.expiresAt).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(payload)
        prefs(context).edit()
            .putString("encrypted_session", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("session_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun readSession(context: Context): BookingSession? {
        if (!isEnabled(context)) return null
        val p = prefs(context)
        val data = p.getString("encrypted_session", null) ?: return null
        val iv = p.getString("session_iv", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            val decoded = JSONObject(
                String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
            )
            BookingSession(decoded.getString("access"), decoded.getString("refresh"),
                decoded.getLong("expiresAt"))
        } catch (_: Exception) {
            // A stale keystore entry cannot be used: erase it without affecting offline records.
            p.edit().remove("encrypted_session").remove("session_iv").apply()
            null
        }
    }

    @Synchronized
    fun countNewPending(context: Context, all: List<WebBooking>): Int {
        if (!isEnabled(context)) return 0
        val p = prefs(context)
        val ids = all.map { it.id }.toSet()
        val remembered = p.getStringSet("seen_ids", null)
        // Baseline the first successful sync rather than alerting on all historic bookings.
        if (remembered == null) {
            p.edit().putStringSet("seen_ids", ids.take(100).toSet()).apply()
            return 0
        }
        val newCount = all.count { it.status == "pending" && it.id !in remembered }
        // Keep recently seen IDs, including confirmed or declined requests, to prevent repeats.
        val recent = (all.map { it.id } + remembered).distinct().take(120).toSet()
        p.edit().putStringSet("seen_ids", recent).apply()
        return newCount
    }

    @Synchronized
    fun enable(context: Context, enabled: Boolean, session: BookingSession?) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        if (!enabled) {
            saveSession(context, null)
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK)
        } else {
            prefs(context).edit().remove("seen_ids").apply()
            saveSession(context, session)
            schedule(context)
        }
    }

    fun schedule(context: Context) {
        if (!isEnabled(context)) return
        val request = PeriodicWorkRequestBuilder<BookingNotificationWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

internal object BookingSystemNotification {
    private const val CHANNEL = "bb_booking_alerts_v1"

    fun show(context: Context, count: Int) {
        if (count < 1 || !BookingAlertStore.isEnabled(context)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Website booking requests", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "New booking requests from the Barber & Beyond website"
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
        )
        val intent = Intent(context, SplashActivity::class.java).apply {
            putExtra("bb_open_bookings", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(context, 708, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val label = if (count == 1) "New website booking request" else "$count new website booking requests"
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.bb_logo)
            .setContentTitle("Barber & Beyond Manager")
            .setContentText(label)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        manager.notify(701, notification)
    }
}

internal class BookingNotificationWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!BookingAlertStore.isEnabled(applicationContext)) return@withContext Result.success()
        val session = BookingAlertStore.readSession(applicationContext)
            ?: return@withContext Result.success()
        try {
            val result = BookingApi.load(session)
            BookingAlertStore.saveSession(applicationContext, result.first)
            val newCount = BookingAlertStore.countNewPending(applicationContext, result.second)
            BookingSystemNotification.show(applicationContext, newCount)
            Result.success()
        } catch (_: Exception) {
            // WorkManager handles offline/retry conditions without touching local business data.
            Result.retry()
        }
    }
}
