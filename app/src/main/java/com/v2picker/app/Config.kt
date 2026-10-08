package com.v2picker.app

import io.nekohasekai.libbox.Libbox
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

const val TEST_URL = "https://www.gstatic.com/generate_204"
const val TUN_ADDR4 = "172.19.0.1"
const val TUN_DNS = "172.19.0.2"
const val TUN_ADDR6 = "fdfe:dcba:9876::1"
const val TUN_MTU = 9000

object Config {
    lateinit var dataDir: File
    val log get() = File(dataDir, "sing-box.log")

    // ---------------- validation (batched + bisect), like the desktop `sing-box check`
    private fun check(obs: List<JSONObject>): Boolean {
        val cfg = JSONObject()
            .put("log", JSONObject().put("disabled", true))
            .put("dns", JSONObject().put("servers", JSONArray().put(JSONObject().put("type", "local").put("tag", "local"))))
            .put("outbounds", JSONArray(obs.mapIndexed { i, ob -> JSONObject(ob.toString()).put("tag", "x$i") }))
            .put("route", JSONObject().put("default_domain_resolver", "local"))
        return runCatching { Libbox.checkConfig(cfg.toString()) }.isSuccess
    }

    fun validateMany(obs: List<JSONObject>): List<Boolean> {
        if (obs.isEmpty()) return emptyList()
        if (check(obs)) return List(obs.size) { true }
        if (obs.size == 1) return listOf(false)
        val mid = obs.size / 2
        return validateMany(obs.subList(0, mid)) + validateMany(obs.subList(mid, obs.size))
    }

    // ---------------- downloads (through our own proxy first when connected, then direct)
    private fun download(url: String, ua: String, proxyPort: Int?, timeoutMs: Int): ByteArray {
        val u = URL(if (url.contains("://")) url.trim() else "https://${url.trim()}")
        val routes = buildList {
            proxyPort?.let { add(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", it))) }
            add(Proxy.NO_PROXY)
        }
        var last: Exception? = null
        for (p in routes) {
            try {
                val c = u.openConnection(p) as HttpURLConnection
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
                c.setRequestProperty("User-Agent", ua); c.setRequestProperty("Accept", "*/*")
                c.inputStream.use { return it.readBytes() }
            } catch (e: Exception) { last = e }
        }
        throw RuntimeException("download failed: ${last?.message}")
    }

    fun fetchSubscription(url: String, proxyPort: Int?) =
        String(download(url, "v2rayN/6.42", proxyPort, 20_000), Charsets.UTF_8)

    // ---------------- Iran bypass rule-sets
    val IR_RULES = mapOf(
        "geosite-ir" to "https://raw.githubusercontent.com/Chocolate4U/Iran-sing-box-rules/rule-set/geosite-ir.srs",
        "geoip-ir" to "https://raw.githubusercontent.com/Chocolate4U/Iran-sing-box-rules/rule-set/geoip-ir.srs",
    )
    fun irPath(tag: String) = File(dataDir, "$tag.srs")
    fun irReady() = IR_RULES.keys.all { irPath(it).let { f -> f.exists() && f.length() > 100 } }
    fun irAgeDays(): Double? = if (!irReady()) null
        else (System.currentTimeMillis() - IR_RULES.keys.minOf { irPath(it).lastModified() }) / 86_400_000.0

    fun downloadIrRules(proxyPort: Int?) {
        for ((tag, url) in IR_RULES) {
            val data = download(url, "V2Picker", proxyPort, 30_000)
            if (data.size <= 100) throw RuntimeException("$tag: empty file")
            val tmp = File(dataDir, "$tag.srs.tmp"); tmp.writeBytes(data); tmp.renameTo(irPath(tag))
        }
    }

    // ---------------- config
    /**
     * vpn=true: tun inbound (Android VpnService) + local mixed proxy. vpn=false: scanner core, proxy only.
     * Per-app mode is enforced by VpnService (allowed apps), so routing stays "everything in tun -> proxy".
     */
    fun build(
        nodes: List<Node>, pool: List<String>?, s: Settings, vpn: Boolean,
        port: Int = s.port, api: String = Core.API, useRulesets: Boolean = true,
    ): JSONObject {
        val tags = nodes.map { it.tag }
        val p = (pool ?: tags).filter { it in tags }.ifEmpty { tags }
        val leak = s.leakGuard && vpn
        val rules = JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        if (leak) rules.put(JSONObject().put("port", 53).put("action", "hijack-dns"))
        rules.put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        if (leak) {
            rules.put(JSONObject().put("protocol", "stun").put("action", "reject"))     // WebRTC real-IP leak
                .put(JSONObject().put("port", 853).put("action", "reject"))             // DoT bypassing us
                .put(JSONObject().put("ip_version", 6).put("action", "reject"))         // IPv6 leak
        }
        val dnsRules = JSONArray()
        val ruleSets = JSONArray()
        if (s.bypassIr && vpn) {
            rules.put(JSONObject().put("domain_suffix", JSONArray().put("ir")).put("outbound", "direct"))
            dnsRules.put(JSONObject().put("domain_suffix", JSONArray().put("ir")).put("server", "local"))
            if (useRulesets && irReady()) {
                IR_RULES.keys.forEach {
                    ruleSets.put(JSONObject().put("type", "local").put("tag", it).put("format", "binary").put("path", irPath(it).absolutePath))
                }
                rules.put(JSONObject().put("rule_set", JSONArray().put("geosite-ir")).put("outbound", "direct"))
                rules.put(JSONObject().put("rule_set", JSONArray().put("geoip-ir")).put("outbound", "direct"))
                dnsRules.put(JSONObject().put("rule_set", JSONArray().put("geosite-ir")).put("server", "local"))
            }
        }
        val outbounds = JSONArray()
            .put(JSONObject().put("type", "selector").put("tag", "proxy").put("outbounds", JSONArray(listOf("auto") + tags))
                .put("default", "auto").put("interrupt_exist_connections", true))
            .put(JSONObject().put("type", "urltest").put("tag", "auto").put("outbounds", JSONArray(p))
                .put("url", TEST_URL).put("interval", "3m").put("tolerance", 50))
        nodes.forEach { outbounds.put(JSONObject(it.ob.toString()).put("tag", it.tag)) }
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        val inbounds = JSONArray().put(JSONObject().put("type", "mixed").put("tag", "mixed-in")
            .put("listen", if (s.allowLan && vpn) "0.0.0.0" else "127.0.0.1").put("listen_port", port))
        if (vpn) {
            val addr = JSONArray().put("$TUN_ADDR4/30")
            if (leak) addr.put("$TUN_ADDR6/126")
            inbounds.put(JSONObject().put("type", "tun").put("tag", "tun-in").put("address", addr).put("mtu", TUN_MTU)
                .put("auto_route", true).put("strict_route", true).put("stack", "mixed"))
        }
        val route = JSONObject().put("rules", rules).put("final", "proxy")
            .put("auto_detect_interface", true)
            .put("default_domain_resolver", JSONObject().put("server", "local").put("strategy", "prefer_ipv4"))
        if (ruleSets.length() > 0) route.put("rule_set", ruleSets)

        return JSONObject()
            .put("log", if (vpn) JSONObject().put("level", "warn").put("output", log.absolutePath).put("timestamp", true)
                        else JSONObject().put("disabled", true))
            .put("experimental", JSONObject().put("clash_api", JSONObject().put("external_controller", api).put("secret", Core.secret)))
            .put("dns", JSONObject()
                .put("servers", JSONArray()
                    .put(JSONObject().put("type", "https").put("tag", "remote").put("server", "1.1.1.1").put("detour", "proxy"))
                    .put(JSONObject().put("type", "local").put("tag", "local")))
                .put("rules", dnsRules).put("final", "remote")
                .put("strategy", if (leak) "ipv4_only" else "prefer_ipv4"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("route", route)
    }
}
