package com.osmarlg.teleprompter

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Mantiene viva la app mientras hay archivos por subir: notificación «Subiendo…» con el avance, aunque se salga de la
 * app, se cambie a otra o se apague la pantalla. Se detiene sola cuando ya no queda nada en fila.
 */
class UploadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Uploader.init(this)
        channel(this)
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "teleprompter:subidas")
                .apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
        }
        if (watcher?.isActive != true) {
            watcher = scope.launch {
                // Si el sistema reinicia el servicio sin la app, retoma la fila.
                if (Uploader.activeCount > 0) Uploader.resumePending(this@UploadService)
                var idle = 0
                while (isActive) {
                    delay(1_000)
                    if (Uploader.activeCount == 0) {
                        if (++idle >= 3) break
                    } else {
                        idle = 0
                    }
                    manager().notify(NOTIFICATION_ID, notification())
                }
                finish()
            }
        }
        return START_STICKY
    }

    private fun finish() {
        val errors = Uploader.errorCount
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (errors > 0) {
            manager().notify(
                DONE_ID,
                base().setContentTitle("$errors archivo(s) no se subieron")
                    .setContentText("Abre «Subidas» en la app para reintentar.")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setAutoCancel(true)
                    .build(),
            )
        }
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    private fun manager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun base(): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_UPLOADS, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL).setContentIntent(open).setOnlyAlertOnce(true)
    }

    private fun notification(): Notification {
        val items = Uploader.items
        val current = items.firstOrNull { it.state == UploadState.SUBIENDO }
        val waiting = Uploader.activeCount
        val title = if (waiting == 0) "Subidas al CRM listas" else "Subiendo $waiting archivo(s) al CRM"
        val text = current?.let { "${it.label} · ${it.progress}%" } ?: "En fila…"
        return base()
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(100, current?.progress ?: 0, current == null)
            .build()
    }

    companion object {
        private const val CHANNEL = "subidas"
        private const val NOTIFICATION_ID = 41
        private const val DONE_ID = 42

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, UploadService::class.java))
            }
        }

        private fun channel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Subidas al CRM", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}
