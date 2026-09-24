package dev.brahmkshatriya.echo.extension

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resilient DNS provider that resolves hostnames through:
 * 1. Standard Android/System DNS
 * 2. Cloudflare DNS-over-HTTPS (1.1.1.1) and Google DoH (8.8.8.8) to bypass ISP DNS filtering
 * 3. Static Anycast IP fallbacks for core API services
 */
class ResilientDns : Dns {

    private val cache = ConcurrentHashMap<String, List<InetAddress>>()

    // Known Anycast Cloudflare IPs for critical domains
    private val staticIps = mapOf(
        "torrentio.strem.fun" to listOf("172.67.164.220", "104.21.10.254"),
        "v3-cinemeta.strem.io" to listOf("104.21.49.121", "172.67.157.170"),
        "graphql.anilist.co" to listOf("104.26.13.77", "104.26.12.77", "172.67.74.87"),
        "api.ani.zip" to listOf("172.67.168.106", "104.21.36.195"),
        "kitsu.io" to listOf("104.26.1.109", "104.26.0.109", "172.67.74.205")
    )

    private val dohEndpoints = listOf(
        "https://1.1.1.1/dns-query",
        "https://1.0.0.1/dns-query",
        "https://8.8.8.8/resolve",
        "https://8.8.4.4/resolve"
    )

    // Direct HTTP client connecting to raw IP addresses (no DNS recursion needed)
    private val directClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val lowerHost = hostname.lowercase()
        cache[lowerHost]?.let { return it }

        // 1. Try system DNS first
        try {
            val addresses = Dns.SYSTEM.lookup(hostname)
            val filtered = addresses.filterNot { it.isLoopbackAddress || it.isAnyLocalAddress }
            if (filtered.isNotEmpty()) {
                cache[lowerHost] = filtered
                return filtered
            }
        } catch (_: Throwable) {
            // System DNS failed (e.g. ISP blocked or offline)
        }

        // 2. Query DNS over HTTPS (DoH) via raw IP
        val dohAddresses = resolveDoH(hostname)
        if (dohAddresses.isNotEmpty()) {
            cache[lowerHost] = dohAddresses
            return dohAddresses
        }

        // 3. Static Anycast IP fallback
        val staticList = staticIps[lowerHost]
        if (!staticList.isNullOrEmpty()) {
            val ips = staticList.mapNotNull { ip ->
                runCatching { InetAddress.getByName(ip) }.getOrNull()
            }
            if (ips.isNotEmpty()) {
                cache[lowerHost] = ips
                return ips
            }
        }

        throw UnknownHostException("ResilientDns: Unable to resolve host '$hostname'")
    }

    private fun resolveDoH(hostname: String): List<InetAddress> {
        for (endpoint in dohEndpoints) {
            try {
                val url = "$endpoint?name=$hostname&type=A"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .build()

                directClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val ipRegex = Regex(""""data"\s*:\s*"([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)"""")
                        val matches = ipRegex.findAll(body)
                        val addresses = matches.mapNotNull { match ->
                            val ip = match.groupValues[1]
                            runCatching { InetAddress.getByName(ip) }.getOrNull()
                        }.toList()
                        if (addresses.isNotEmpty()) return addresses
                    }
                }
            } catch (_: Throwable) {}
        }
        return emptyList()
    }
}
