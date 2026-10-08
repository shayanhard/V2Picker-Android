package com.v2picker.app

import org.json.JSONArray
import org.json.JSONObject

data class Node(
    val tag: String,
    val name: String,
    val proto: String,
    val ob: JSONObject,
    val link: String,
    val sub: Int? = null,
    val fav: Boolean = false,
    val delay: Int? = null,     // null = never tested, 0 = dead
    val ts: Long = 0,           // when delay was measured (ms)
) {
    val tested get() = ts > 0
    val alive get() = (delay ?: 0) > 0

    fun toJson(): JSONObject = JSONObject().put("tag", tag).put("name", name).put("proto", proto)
        .put("ob", ob).put("link", link).put("fav", fav).put("ts", ts)
        .also { j -> sub?.let { j.put("sub", it) }; delay?.let { j.put("delay", it) } }

    companion object {
        fun fromJson(j: JSONObject) = Node(
            j.getString("tag"), j.optString("name"), j.optString("proto"), j.getJSONObject("ob"), j.optString("link"),
            if (j.has("sub")) j.getInt("sub") else null, j.optBoolean("fav"),
            if (j.has("delay") && !j.isNull("delay")) j.getInt("delay") else null, j.optLong("ts"),
        )
    }
}

enum class VpnMode { ALL, APPS }

data class Settings(
    val port: Int = 2080,
    val mode: VpnMode = VpnMode.ALL,
    val apps: List<String> = listOf("org.telegram.messenger", "com.android.chrome"),
    val allowLan: Boolean = false,
    val bypassIr: Boolean = true,
    val leakGuard: Boolean = true,
    val subs: List<String> = listOf("", "", ""),
) {
    fun toJson(): JSONObject = JSONObject().put("port", port).put("mode", mode.name).put("apps", JSONArray(apps))
        .put("allow_lan", allowLan).put("bypass_ir", bypassIr).put("leak_guard", leakGuard).put("subs", JSONArray(subs))

    companion object {
        fun fromJson(j: JSONObject): Settings {
            val d = Settings()
            fun list(k: String, def: List<String>) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: def
            val subs = list("subs", d.subs).take(SUB_SLOTS)
            return Settings(
                j.optInt("port", d.port), runCatching { VpnMode.valueOf(j.getString("mode")) }.getOrDefault(d.mode),
                list("apps", d.apps), j.optBoolean("allow_lan", d.allowLan), j.optBoolean("bypass_ir", d.bypassIr),
                j.optBoolean("leak_guard", d.leakGuard), subs + List(SUB_SLOTS - subs.size) { "" },
            )
        }
    }
}

const val SUB_SLOTS = 3
