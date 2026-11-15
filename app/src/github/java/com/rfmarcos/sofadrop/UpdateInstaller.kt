package com.rfmarcos.sofadrop

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import okhttp3.Request
import java.io.IOException

/** Installs the release found by [Updater.check]. Only in GitHub builds: Google Play's can't update themselves. */
object UpdateInstaller {

    /**
     * Download the new APK and start the system installer (blocking: call it off the main thread).
     * Without the "install unknown apps" permission, Android shows where to grant it first.
     */
    fun install(context: Context) {
        val request = Request.Builder().url(Settings.updateApkUrl).build()
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                Updater.http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty download")
                    session.openWrite("sofadrop.apk", 0, body.contentLength()).use { out ->
                        body.byteStream().copyTo(out)
                        session.fsync(out)
                    }
                }
                val callback = PendingIntent.getBroadcast(
                    context, sessionId, Intent(context, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        }
    }

    /**
     * Result of [install]: the system's confirmation, started from the open settings screen ([Updater.installListener]):
     * started from here, in the background, Android may not show it (seen on an NVIDIA Shield as nothing happening).
     * Or why it failed. Nothing is ever dropped without a word.
     */
    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    val listener = Updater.installListener
                    when {
                        confirm == null -> fail(context, "no confirmation from Android")
                        listener != null -> listener.confirm(confirm)
                        else -> try {
                            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {
                            fail(context, e.message ?: e.javaClass.simpleName)
                        }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // Android replaces the app, which closes (Android TV 11 goes home)
                else -> fail(context, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().ifBlank { status.toString() })
            }
        }

        private fun fail(context: Context, message: String) {
            Log.w("SofadropUpdater", "Install failed: $message")
            val listener = Updater.installListener
            if (listener != null) return listener.failed(message)
            // Behind the system installer: a toast now, and the update row once Sofadrop's back in front
            Updater.unheardFailure = message
            Toast.makeText(context, context.getString(R.string.update_failed, message), Toast.LENGTH_LONG).show()
        }
    }
}
