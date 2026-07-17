package com.newash.videocast.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/** Bounded whole-stream read: an oversized (mispicked) source fails instead of eating the heap. */
fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) return out.toByteArray()
        out.write(buffer, 0, read)
        if (out.size() > limit) throw IOException("larger than ${limit / (1 shl 20)} MB — refusing to read whole")
    }
}

/**
 * The phone's LAN IPv4 address, preferring Wi-Fi (and hotspot) interfaces.
 * This is the address the Chromecast will fetch media from, so it must be
 * site-local and actually on the LAN — VPN/cellular tunnels are excluded
 * (their 10.x addresses are unreachable from the TV).
 */
fun localIpv4(): String? = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
    .getOrNull().orEmpty()
    .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
    .filterNot { it.name.startsWithAny("tun", "ppp", "rmnet") }
    .sortedBy { if (it.name.startsWithAny("wlan", "ap", "swlan")) 0 else 1 }
    .firstNotNullOfOrNull { nic ->
        nic.inetAddresses.toList()
            .filterIsInstance<Inet4Address>()
            .firstOrNull(Inet4Address::isSiteLocalAddress)
            ?.hostAddress
    }

private fun String.startsWithAny(vararg prefixes: String): Boolean = prefixes.any(::startsWith)
