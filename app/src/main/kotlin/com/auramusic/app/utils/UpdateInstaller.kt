package com.auramusic.app.utils

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the new APK inside the app and hands it to the system installer. Once this app has
 * installed itself (Android 12+), later updates need no tap: USER_ACTION_NOT_REQUIRED.
 */
object UpdateInstaller {
    private const val ACTION = "com.auramusic.app.UPDATE_RESULT"

    suspend fun downloadAndInstall(context: Context, url: String, versionName: String): Boolean =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val apk = File(app.cacheDir, "update-$versionName.apk")
            try {
                if (!apk.exists() || apk.length() == 0L) {
                    val tmp = File(app.cacheDir, "update.part")
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    app.cacheDir.listFiles { f -> f.name.startsWith("update-") }?.forEach { it.delete() }
                    tmp.renameTo(apk)
                }
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    apk.inputStream().use { input ->
                        session.openWrite("update.apk", 0, apk.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    val intent = Intent(app, UpdateResultReceiver::class.java).setAction(ACTION)
                    val pending = PendingIntent.getBroadcast(
                        app, id, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(pending.intentSender)
                }
                true
            } catch (e: Exception) {
                Timber.e(e, "UpdateInstaller failed")
                false
            }
        }
}

class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // First time (or Android < 12): the system asks the user to confirm once.
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            runCatching { confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity) }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            Timber.e("Update install status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
