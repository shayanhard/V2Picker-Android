package com.v2picker.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Runs the live sing-box core with a tun device (Android VPN). */
class BoxVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var box: BoxService? = null
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopCore(); stopSelf(); return START_NOT_STICKY }
            else -> {
                foreground()
                val cfg = intent?.getStringExtra(EXTRA_CONFIG) ?: Core.pendingConfig
                if (cfg == null) { stopSelf(); return START_NOT_STICKY }
                scope.launch { startCore(cfg) }
            }
        }
        return START_STICKY
    }

    private fun startCore(cfg: String) {
        stopCore(keepState = true)
        try {
            Config.log.delete()
            val svc = Libbox.newService(cfg, Platform(this, this))
            svc.start()
            box = svc
            Core.onCoreStarted(null)
        } catch (e: Exception) {
            stopCore(keepState = true)
            Core.onCoreStarted(e.message ?: e.toString())
            stopSelf()
        }
    }

    /** Called by sing-box when it wants the tun device. */
    fun openTun(): Int {
        if (prepare(this) != null) throw IllegalStateException("VPN permission was revoked")
        val s = Core.settings.value
        val b = Builder().setSession("V2Picker").setMtu(TUN_MTU)
            .addAddress(TUN_ADDR4, 30).addRoute("0.0.0.0", 0).addDnsServer(TUN_DNS)
        if (s.leakGuard) b.addAddress(TUN_ADDR6, 126).addRoute("::", 0)   // grab IPv6 so it gets rejected, not leaked
        if (s.mode == VpnMode.APPS && s.apps.isNotEmpty()) {
            s.apps.forEach { runCatching { b.addAllowedApplication(it) } }
        } else {
            b.addDisallowedApplication(packageName)   // our own downloads go direct / through the local port
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) b.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("127.0.0.0"), 8))
        val pfd = b.establish() ?: throw IllegalStateException("VPN could not start (permission?)")
        tun = pfd
        return pfd.fd
    }

    private fun stopCore(keepState: Boolean = false) {
        runCatching { box?.close() }; box = null
        runCatching { tun?.close() }; tun = null
        if (!keepState) Core.onCoreStopped()
    }

    override fun onRevoke() { stopCore(); stopSelf() }   // another VPN took over / user revoked
    override fun onDestroy() { stopCore(); scope.cancel(); super.onDestroy() }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel("vpn", "VPN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BoxVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "vpn").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("V2Picker connected").setContentIntent(open).setOngoing(true)
            .addAction(0, "Disconnect", stop).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val EXTRA_CONFIG = "cfg"
    }
}
