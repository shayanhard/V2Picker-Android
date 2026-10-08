package com.v2picker.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Talks to sing-box's clash API: delay tests, current node, switching, traffic stream. */
class Clash(private val api: String) {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun open(path: String, timeoutMs: Int, method: String = "GET"): HttpURLConnection =
        (URL("http://$api$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = timeoutMs; readTimeout = timeoutMs
            setRequestProperty("Authorization", "Bearer ${Core.secret}")
        }

    private fun get(path: String, timeoutMs: Int = 3000): JSONObject =
        open(path, timeoutMs).inputStream.use { JSONObject(String(it.readBytes()).ifBlank { "{}" }) }

    fun alive() = runCatching { get("/version", 500); true }.getOrDefault(false)

    fun delay(tag: String, timeoutMs: Int = 5000): Int? = runCatching {
        get("/proxies/${enc(tag)}/delay?timeout=$timeoutMs&url=${enc(TEST_URL)}", timeoutMs + 2000).optInt("delay").takeIf { it > 0 }
    }.getOrNull()

    /** (real node tag, isAuto) */
    fun current(): Pair<String?, Boolean> = runCatching {
        val sel = get("/proxies/proxy").optString("now")
        if (sel == "auto") get("/proxies/auto").optString("now").ifEmpty { null } to true else sel to false
    }.getOrDefault(null to true)

    fun select(tag: String, group: String = "proxy") {
        val c = open("/proxies/${enc(group)}", 3000, "PUT")
        c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("name", tag).toString().toByteArray()) }
        c.inputStream.use { it.readBytes() }
    }

    /** Blocks, calling cb(up, down) every second, until the stream breaks or stop() returns true. */
    fun streamTraffic(stop: () -> Boolean, cb: (Long, Long) -> Unit) {
        open("/traffic", 5000).inputStream.bufferedReader().use { r ->
            while (!stop()) {
                val line = r.readLine() ?: break
                runCatching { JSONObject(line).let { cb(it.optLong("up"), it.optLong("down")) } }
            }
        }
    }
}
