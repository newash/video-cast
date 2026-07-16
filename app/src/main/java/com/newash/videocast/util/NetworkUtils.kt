package com.newash.videocast.util

import java.net.Inet4Address
import java.net.NetworkInterface

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
