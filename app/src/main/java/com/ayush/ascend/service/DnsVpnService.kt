package com.ayush.ascend.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.ayush.ascend.AscendApp
import com.ayush.ascend.R
import com.ayush.ascend.core.graph
import com.ayush.ascend.data.Feature
import com.ayush.ascend.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * #6 Local DNS firewall. Only DNS traffic (to a fake resolver 10.120.0.53) is routed into the tunnel;
 * blocked domains get NXDOMAIN, everything else is forwarded to the upstream resolver.
 * No other traffic touches the tunnel and nothing leaves the phone except normal DNS lookups.
 */
class DnsVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile private var upstream: InetAddress = InetAddress.getByName(FAMILY_DNS)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        if (tun != null) return START_STICKY
        startForegroundCompat()
        tun = Builder()
            .setSession("Ascend DNS firewall")
            .addAddress(TUN_ADDR, 32)
            .addDnsServer(DNS_ADDR)
            .addRoute(DNS_ADDR, 32)
            .addDisallowedApplication(packageName)
            .setBlocking(true)
            .establish()
        val fd = tun ?: run { stopSelf(); return START_NOT_STICKY }
        running = true
        scope.launch { graph.db.domains().observe().collectLatest { list -> blocked = list.map { it.domain }.toSet() } }
        scope.launch {
            graph.prefs.settings.collectLatest { s ->
                upstream = InetAddress.getByName(if (s.on(Feature.ADULT)) FAMILY_DNS else s.dnsUpstream)
            }
        }
        scope.launch { loop(fd) }
        return START_STICKY
    }

    private suspend fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (scope.isActive) {
            val n = runCatching { input.read(buf) }.getOrDefault(-1)
            if (n <= 0) break
            val pkt = DnsPacket.parse(buf, n) ?: continue
            val name = pkt.queryName
            if (name != null && isBlocked(name)) {
                synchronized(output) { output.write(pkt.reply(DnsPacket.nxdomain(pkt.dns))) }
                continue
            }
            val query = pkt.dns
            scope.launch {
                runCatching {
                    DatagramSocket().use { sock ->
                        protect(sock)
                        sock.soTimeout = 4_000
                        sock.send(DatagramPacket(query, query.size, upstream, 53))
                        val resp = ByteArray(4096)
                        val dp = DatagramPacket(resp, resp.size)
                        sock.receive(dp)
                        synchronized(output) { output.write(pkt.reply(resp.copyOf(dp.length))) }
                    }
                }
            }
        }
    }

    private fun isBlocked(name: String): Boolean {
        var d = name.lowercase().trimEnd('.')
        while (true) {
            if (d in blocked) return true
            val i = d.indexOf('.')
            if (i < 0) return false
            d = d.substring(i + 1)
        }
    }

    private fun startForegroundCompat() {
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, AscendApp.CH_GUARD)
            .setSmallIcon(R.drawable.ic_monochrome)
            .setContentTitle("DNS firewall on")
            .setContentText("Blocking your distracting sites in every app & browser")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(9, n)
    }

    private fun shutdown() {
        running = false
        runCatching { tun?.close() }
        tun = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() { shutdown(); super.onRevoke() }
    override fun onDestroy() { if (running) shutdown(); super.onDestroy() }

    companion object {
        private const val TUN_ADDR = "10.120.0.1"
        private const val DNS_ADDR = "10.120.0.53"
        /** Cloudflare for Families: resolves normally but blocks adult + malware domains. */
        private const val FAMILY_DNS = "1.1.1.3"
        private const val ACTION_STOP = "stop"
        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, DnsVpnService::class.java)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** Minimal IPv4/UDP/DNS packet handling for the tunnel. */
class DnsPacket private constructor(
    private val srcIp: ByteArray,
    private val dstIp: ByteArray,
    private val srcPort: Int,
    private val dstPort: Int,
    val dns: ByteArray,
) {
    val queryName: String? get() = runCatching {
        if (dns.size < 13) return null
        val sb = StringBuilder()
        var i = 12
        while (i < dns.size) {
            val len = dns[i].toInt() and 0xff
            if (len == 0) break
            if (len and 0xC0 != 0) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, i + 1, len, Charsets.US_ASCII))
            i += len + 1
        }
        sb.toString()
    }.getOrNull()

    /** Wraps a DNS payload in IPv4+UDP with source/destination swapped. */
    fun reply(payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45; p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[8] = 64; p[9] = 17
        System.arraycopy(dstIp, 0, p, 12, 4)
        System.arraycopy(srcIp, 0, p, 16, 4)
        val cs = checksum(p, 0, 20); p[10] = (cs shr 8).toByte(); p[11] = cs.toByte()
        p[20] = (dstPort shr 8).toByte(); p[21] = dstPort.toByte()
        p[22] = (srcPort shr 8).toByte(); p[23] = srcPort.toByte()
        val udpLen = 8 + payload.size
        p[24] = (udpLen shr 8).toByte(); p[25] = udpLen.toByte()
        // UDP checksum 0 = "not computed", valid for IPv4
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    companion object {
        fun parse(b: ByteArray, n: Int): DnsPacket? {
            if (n < 28) return null
            if ((b[0].toInt() shr 4) != 4) return null
            val ihl = (b[0].toInt() and 0x0f) * 4
            if (b[9].toInt() != 17 || n < ihl + 8) return null
            val dstPort = ((b[ihl + 2].toInt() and 0xff) shl 8) or (b[ihl + 3].toInt() and 0xff)
            if (dstPort != 53) return null
            val srcPort = ((b[ihl].toInt() and 0xff) shl 8) or (b[ihl + 1].toInt() and 0xff)
            return DnsPacket(b.copyOfRange(12, 16), b.copyOfRange(16, 20), srcPort, dstPort, b.copyOfRange(ihl + 8, n))
        }

        fun nxdomain(query: ByteArray): ByteArray {
            val r = query.copyOf()
            r[2] = (0x80 or (query[2].toInt() and 0x01)).toByte() // QR=1, keep RD
            r[3] = 0x83.toByte()                                   // RA=1, RCODE=3
            r[6] = 0; r[7] = 0; r[8] = 0; r[9] = 0; r[10] = 0; r[11] = 0
            return r
        }

        private fun checksum(b: ByteArray, off: Int, len: Int): Int {
            var sum = 0
            var i = off
            while (i < off + len) { sum += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff); i += 2 }
            while (sum shr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
            return sum.inv() and 0xffff
        }
    }
}
