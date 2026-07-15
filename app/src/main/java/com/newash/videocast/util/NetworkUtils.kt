package com.newash.videocast.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The phone's LAN IPv4 address, preferring the Wi-Fi interface. This is the
 * address the Chromecast will fetch media from, so it must be site-local.
 */
fun localIpv4(): String? = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
    .getOrDefault(emptyList())
    .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
    .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
    .firstNotNullOfOrNull { nic ->
        nic.inetAddresses.toList()
            .filterIsInstance<Inet4Address>()
            .firstOrNull(Inet4Address::isSiteLocalAddress)
            ?.hostAddress
    }
