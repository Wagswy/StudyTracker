package com.example.studytracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class DomainMonitorVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var isRunning = false

    companion object {
        val loggedDomains: MutableList<String> = Collections.synchronizedList(mutableListOf())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_VPN") {
            stopVpn()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        if (!isRunning) {
            isRunning = true
            thread(start = true) { runVpnLoop() }
            thread(start = true) { runPeriodicCloudSync() }
        }
        return START_STICKY
    }

    // Automatically push updated data to Firebase every 5 minutes while running
    private fun runPeriodicCloudSync() {
        val helper = TelemetryHelper(applicationContext)
        while (isRunning) {
            helper.syncReportToFirebase()
            try {
                Thread.sleep(5 * 60 * 1000L) // 5 minutes
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun startForegroundNotification() {
        val channelId = "vpn_monitor_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Study Domain Logger",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Study Accountability Active")
            .setContentText("Logging domain activity & syncing study stats.")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun runVpnLoop() {
        try {
            val builder = Builder()
                .setSession("StudyDomainMonitor")
                .addAddress("10.0.0.2", 32)
                .addDnsServer("8.8.8.8")
                .addRoute("8.8.8.8", 32)

            vpnInterface = builder.establish() ?: return

            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val packet = ByteBuffer.allocate(32767)

            while (isRunning) {
                val length = inputStream.read(packet.array())
                if (length > 0) {
                    packet.limit(length)
                    handleIpPacket(packet, outputStream)
                    packet.clear()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stopVpn()
        }
    }

    private fun handleIpPacket(packet: ByteBuffer, vpnOutput: FileOutputStream) {
        val raw = packet.array()
        val version = (raw[0].toInt() shr 4) and 0x0F
        if (version != 4) return

        val ihl = (raw[0].toInt() and 0x0F) * 4
        val protocol = raw[9].toInt() and 0xFF
        if (protocol != 17) return

        val destPort = ((raw[ihl + 2].toInt() and 0xFF) shl 8) or (raw[ihl + 3].toInt() and 0xFF)
        if (destPort != 53) return

        val dnsPayloadOffset = ihl + 8
        val dnsPayloadLength = packet.limit() - dnsPayloadOffset
        if (dnsPayloadLength <= 12) return

        val domain = parseDnsDomain(raw, dnsPayloadOffset + 12, packet.limit())
        if (domain.isNotEmpty()) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = "[$time] $domain"
            if (loggedDomains.isEmpty() || loggedDomains[0] != entry) {
                loggedDomains.add(0, entry)
                if (loggedDomains.size > 200) loggedDomains.removeAt(loggedDomains.lastIndex)
            }
        }

        val requestCopy = raw.copyOfRange(0, packet.limit())
        thread(start = true) {
            forwardDnsQuery(requestCopy, ihl, dnsPayloadOffset, dnsPayloadLength, vpnOutput)
        }
    }

    private fun parseDnsDomain(data: ByteArray, startOffset: Int, maxLimit: Int): String {
        val sb = StringBuilder()
        var pos = startOffset
        while (pos < maxLimit) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) break
            pos++
            if (pos + len > maxLimit) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(data, pos, len, Charsets.UTF_8))
            pos += len
        }
        return sb.toString()
    }

    private fun forwardDnsQuery(
        originalIpPacket: ByteArray,
        ihl: Int,
        dnsOffset: Int,
        dnsLen: Int,
        vpnOutput: FileOutputStream
    ) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 3000

            val dnsRequest = originalIpPacket.copyOfRange(dnsOffset, dnsOffset + dnsLen)
            val outPacket = DatagramPacket(dnsRequest, dnsRequest.size, InetAddress.getByName("8.8.8.8"), 53)
            socket.send(outPacket)

            val responseBuf = ByteArray(1024)
            val inPacket = DatagramPacket(responseBuf, responseBuf.size)
            socket.receive(inPacket)

            val replyPacket = buildUdpIpResponse(originalIpPacket, ihl, responseBuf, inPacket.length)
            synchronized(vpnOutput) {
                vpnOutput.write(replyPacket)
            }
        } catch (_: Exception) {
        } finally {
            socket?.close()
        }
    }

    private fun buildUdpIpResponse(
        reqPacket: ByteArray,
        ihl: Int,
        dnsReply: ByteArray,
        dnsReplyLen: Int
    ): ByteArray {
        val udpLen = 8 + dnsReplyLen
        val totalLen = ihl + udpLen
        val resp = ByteArray(totalLen)

        System.arraycopy(reqPacket, 0, resp, 0, ihl)
        System.arraycopy(reqPacket, 12, resp, 16, 4)
        System.arraycopy(reqPacket, 16, resp, 12, 4)

        resp[2] = ((totalLen shr 8) and 0xFF).toByte()
        resp[3] = (totalLen and 0xFF).toByte()

        resp[10] = 0
        resp[11] = 0
        var sum = 0
        for (i in 0 until ihl step 2) {
            val word = ((resp[i].toInt() and 0xFF) shl 8) or (resp[i + 1].toInt() and 0xFF)
            sum += word
        }
        while ((sum shr 16) > 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val checksum = sum.inv() and 0xFFFF
        resp[10] = ((checksum shr 8) and 0xFF).toByte()
        resp[11] = (checksum and 0xFF).toByte()

        resp[ihl] = reqPacket[ihl + 2]
        resp[ihl + 1] = reqPacket[ihl + 3]
        resp[ihl + 2] = reqPacket[ihl]
        resp[ihl + 3] = reqPacket[ihl + 1]

        resp[ihl + 4] = ((udpLen shr 8) and 0xFF).toByte()
        resp[ihl + 5] = (udpLen and 0xFF).toByte()
        resp[ihl + 6] = 0
        resp[ihl + 7] = 0

        System.arraycopy(dnsReply, 0, resp, ihl + 8, dnsReplyLen)
        return resp
    }

    private fun stopVpn() {
        isRunning = false
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
