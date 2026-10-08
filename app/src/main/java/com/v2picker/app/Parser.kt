package com.v2picker.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/** v2ray share links -> sing-box outbounds. vmess, vless (tls/reality), trojan, ss, hysteria2/hy2, tuic. */
object Parser {

    data class Parsed(val name: String, val proto: String, val ob: JSONObject)

    fun b64d(input: String): String {
        var s = input.trim().replace('-', '+').replace('_', '/').replace("\n", "").replace("\r", "")
        s += "=".repeat((4 - s.length % 4) % 4)
        return String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun unq(s: String?): String = try { URLDecoder.decode((s ?: "").replace("+", "%2B"), "UTF-8") } catch (_: Exception) { s ?: "" }

    /** Pasted text -> list of links. Also handles a base64 subscription blob. */
    fun splitInput(raw: String): List<String> {
        var text = raw.trim()
        if (!text.contains("://")) runCatching { text = b64d(text) }
        val links = mutableListOf<String>()
        for (l in text.lines()) {
            val line = l.trim()
            if (line.contains("://")) links += line
            else if (line.length > 20) runCatching {
                links += b64d(line).lines().map { it.trim() }.filter { it.contains("://") }
            }
        }
        return links.distinct()
    }

    /** Identity of a config, ignoring its display name. Same server+creds+transport = duplicate. */
    fun nodeKey(ob: JSONObject): String = canonical(ob.let { JSONObject(it.toString()).apply { remove("tag") } })

    private fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { "\"$it\":" + canonical(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        is String -> JSONObject.quote(v)
        null -> "null"
        else -> v.toString()
    }

    private fun truthy(v: String?) = (v ?: "").lowercase() in setOf("1", "true", "yes")

    private fun makeTls(q: Map<String, String>, defaultSni: String, security: String?): JSONObject? {
        val sec = (security ?: "").lowercase()
        if (sec !in setOf("tls", "reality", "xtls")) return null
        val sni = q["sni"].orEmpty().ifEmpty { q["peer"].orEmpty() }.ifEmpty { q["host"].orEmpty().split(",")[0] }.ifEmpty { defaultSni }
        val t = JSONObject().put("enabled", true).put("server_name", sni)
        if (truthy(q["allowInsecure"]) || truthy(q["insecure"])) t.put("insecure", true)
        q["alpn"]?.takeIf { it.isNotEmpty() }?.let { a -> t.put("alpn", JSONArray(a.split(",").filter { it.isNotEmpty() })) }
        var fp = q["fp"].orEmpty()
        if (sec == "reality") {
            t.put("reality", JSONObject().put("enabled", true).put("public_key", q["pbk"].orEmpty()).put("short_id", q["sid"].orEmpty()))
            if (fp.isEmpty()) fp = "chrome"
        }
        if (fp.isNotEmpty()) t.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
        return t
    }

    private fun makeTransport(netIn: String?, q: Map<String, String>): JSONObject? {
        val net = (netIn ?: "tcp").lowercase()
        val host = q["host"].orEmpty()
        val path = q["path"].orEmpty().ifEmpty { "/" }
        when (net) {
            "tcp", "raw", "" -> {
                if ((q["headerType"] ?: q["type_header"] ?: "none") == "http") throw IllegalArgumentException("tcp+http header obfs not supported")
                return null
            }
            "ws" -> {
                val t = JSONObject().put("type", "ws").put("path", path)
                if (path.contains("?ed=")) {
                    val p = path.substringBefore("?ed="); val ed = path.substringAfter("?ed=")
                    t.put("path", p.ifEmpty { "/" })
                    ed.substringBefore("&").toIntOrNull()?.let {
                        t.put("max_early_data", it).put("early_data_header_name", "Sec-WebSocket-Protocol")
                    }
                }
                if (host.isNotEmpty()) t.put("headers", JSONObject().put("Host", host))
                return t
            }
            "grpc" -> return JSONObject().put("type", "grpc")
                .put("service_name", q["serviceName"].orEmpty().ifEmpty { q["path"].orEmpty().trim('/') })
            "h2", "http" -> {
                val t = JSONObject().put("type", "http").put("path", path)
                if (host.isNotEmpty()) t.put("host", JSONArray(host.split(",")))
                return t
            }
            "httpupgrade" -> {
                val t = JSONObject().put("type", "httpupgrade").put("path", path)
                if (host.isNotEmpty()) t.put("host", host)
                return t
            }
        }
        throw IllegalArgumentException("transport \"$net\" not supported")
    }

    private class Url(val user: String, val pass: String?, val host: String, val port: Int?, val q: Map<String, String>, val name: String)

    /** Tolerant URL split (java.net.URI chokes on emoji/spaces in names). */
    private fun url(link: String): Url {
        var rest = link.substringAfter("://")
        val name = if (rest.contains('#')) unq(rest.substringAfter('#')).also { rest = rest.substringBefore('#') } else ""
        val query = if (rest.contains('?')) rest.substringAfter('?').also { rest = rest.substringBefore('?') } else ""
        rest = rest.substringBefore('/')
        val auth = if (rest.contains('@')) rest.substringBeforeLast('@') else ""
        val hp = rest.substringAfterLast('@')
        val (host, port) = if (hp.startsWith("[")) {
            hp.substringAfter('[').substringBefore(']') to hp.substringAfter("]:", "").toIntOrNull()
        } else if (hp.contains(':')) hp.substringBeforeLast(':') to hp.substringAfterLast(':').toIntOrNull()
        else hp to null
        val q = query.split('&').filter { it.isNotEmpty() }.associate { kv ->
            unq(kv.substringBefore('=')) to unq(kv.substringAfter('=', ""))
        }
        val user = if (auth.contains(':')) auth.substringBefore(':') else auth
        val pass = if (auth.contains(':')) auth.substringAfter(':') else null
        return Url(unq(user), pass?.let { unq(it) }, host, port, q, name)
    }

    fun parseLink(raw: String): Parsed {
        val link = raw.trim()
        val scheme = link.substringBefore("://").lowercase()
        when (scheme) {
            "vmess" -> {
                val j = JSONObject(b64d(link.substring(8)))
                val q = j.keys().asSequence().associateWith { j.opt(it)?.toString().orEmpty() }
                val add = j.getString("add")
                val ob = JSONObject().put("type", "vmess").put("server", add)
                    .put("server_port", j.get("port").toString().toInt()).put("uuid", j.getString("id"))
                    .put("security", j.optString("scy").ifEmpty { "auto" })
                    .put("alter_id", j.optString("aid").ifEmpty { "0" }.toIntOrNull() ?: 0)
                val net = j.optString("net", "tcp")
                if (net == "tcp" && j.optString("type") == "http") throw IllegalArgumentException("tcp+http header obfs not supported")
                makeTransport(net, q)?.let { ob.put("transport", it) }
                makeTls(q, add, j.optString("tls"))?.let { ob.put("tls", it) }
                return Parsed(j.optString("ps").ifEmpty { add }, "vmess", ob)
            }
            "vless", "trojan" -> {
                val u = url(link)
                val ob = JSONObject().put("type", scheme).put("server", u.host).put("server_port", u.port ?: 443)
                val sec: String
                if (scheme == "vless") {
                    ob.put("uuid", u.user)
                    q(u, "flow")?.let { ob.put("flow", it) }
                    ob.put("packet_encoding", "xudp")
                    sec = u.q["security"] ?: "none"
                } else {
                    ob.put("password", u.user)
                    sec = u.q["security"] ?: "tls"
                }
                makeTransport(u.q["type"] ?: "tcp", u.q)?.let { ob.put("transport", it) }
                makeTls(u.q, u.host, sec)?.let { ob.put("tls", it) }
                return Parsed(u.name.ifEmpty { u.host }, scheme, ob)
            }
            "ss" -> {
                var rest = link.substring(5)
                var name = ""
                if (rest.contains('#')) { name = unq(rest.substringAfter('#')); rest = rest.substringBefore('#') }
                val query = rest.substringAfter('?', "")
                rest = rest.substringBefore('?')
                if (query.split('&').any { it.substringBefore('=') == "plugin" }) throw IllegalArgumentException("ss plugin not supported")
                rest = rest.trimEnd('/')
                var ui: String; val hp: String
                if (rest.contains('@')) {
                    ui = unq(rest.substringBeforeLast('@')); hp = rest.substringAfterLast('@')
                    if (!ui.contains(':')) ui = b64d(ui)
                } else {
                    val d = b64d(rest); ui = d.substringBeforeLast('@'); hp = d.substringAfterLast('@')
                }
                val method = ui.substringBefore(':'); val pw = ui.substringAfter(':')
                val host = hp.substringBeforeLast(':').trim('[', ']'); val port = hp.substringAfterLast(':').toInt()
                val ob = JSONObject().put("type", "shadowsocks").put("server", host).put("server_port", port)
                    .put("method", method).put("password", pw)
                return Parsed(name.ifEmpty { host }, "ss", ob)
            }
            "hysteria2", "hy2" -> {
                val u = url(link)
                val pw = if (u.pass != null) "${u.user}:${u.pass}" else u.user
                val ob = JSONObject().put("type", "hysteria2").put("server", u.host).put("server_port", u.port ?: 443)
                    .put("password", pw)
                    .put("tls", JSONObject().put("enabled", true).put("server_name", u.q["sni"].orEmpty().ifEmpty { u.host })
                        .put("insecure", truthy(u.q["insecure"] ?: "0")).put("alpn", JSONArray(listOf("h3"))))
                if (u.q["obfs"] == "salamander")
                    ob.put("obfs", JSONObject().put("type", "salamander").put("password", u.q["obfs-password"].orEmpty()))
                return Parsed(u.name.ifEmpty { u.host }, "hysteria2", ob)
            }
            "tuic" -> {
                val u = url(link)
                val ob = JSONObject().put("type", "tuic").put("server", u.host).put("server_port", u.port ?: 443)
                    .put("uuid", u.user).put("password", u.pass.orEmpty())
                    .put("congestion_control", u.q["congestion_control"] ?: "bbr")
                    .put("udp_relay_mode", u.q["udp_relay_mode"] ?: "native")
                    .put("tls", JSONObject().put("enabled", true).put("server_name", u.q["sni"].orEmpty().ifEmpty { u.host })
                        .put("alpn", JSONArray((u.q["alpn"] ?: "h3").split(",")))
                        .put("insecure", truthy(u.q["allow_insecure"] ?: u.q["insecure"] ?: "0")))
                return Parsed(u.name.ifEmpty { u.host }, "tuic", ob)
            }
        }
        throw IllegalArgumentException("protocol \"$scheme\" not supported")
    }

    private fun q(u: Url, k: String) = u.q[k]?.takeIf { it.isNotEmpty() }
}
