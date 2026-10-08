package com.v2picker.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.ArrayDeque

enum class State { OFF, CONNECTING, ON }

data class Live(
    val tag: String? = null, val isAuto: Boolean = true, val delay: Int? = null,
    val up: Long = 0, val down: Long = 0, val history: List<Int?> = emptyList(),
)

/** Everything the desktop MainWindow did, minus the widgets. Lives as long as the process. */
object Core {
    const val API = "127.0.0.1:19090"
    private const val SCAN_API = "127.0.0.1:19091"
    private const val SCAN_PORT = 20899
    private const val SCAN_STALE_MS = 6 * 3600_000L
    private const val RETEST_TOP = 60
    private const val WORKERS = 16

    lateinit var secret: String
    private lateinit var app: Context
    private lateinit var nodesFile: File
    private lateinit var settingsFile: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val nodes = MutableStateFlow<List<Node>>(emptyList())
    val settings = MutableStateFlow(Settings())
    private val _state = MutableStateFlow(State.OFF); val state = _state.asStateFlow()
    private val _live = MutableStateFlow(Live()); val live = _live.asStateFlow()
    private val _logs = MutableStateFlow<List<String>>(emptyList()); val logs = _logs.asStateFlow()
    val scan = MutableStateFlow<Pair<Int, Int>?>(null)          // done / total while scanning
    val subStatus = MutableStateFlow(List(SUB_SLOTS) { "" })
    val busySubs = MutableStateFlow(setOf<Int>())

    @Volatile var pendingConfig: String? = null
    private var liveTags = setOf<String>()
    private var scanJob: Job? = null
    private var monitorJob: Job? = null
    private var tagSeq = 0
    private var connectAfterScan = false
    private var retested = false
    private val clash = Clash(API)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val data = File(app.filesDir, "data").apply { mkdirs() }
        Config.dataDir = data
        Libbox.setup(io.nekohasekai.libbox.SetupOptions().apply {
            basePath = app.filesDir.absolutePath
            workingPath = data.absolutePath
            tempPath = app.cacheDir.absolutePath
        })
        val sf = File(data, "api.secret")
        secret = sf.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null } ?: ByteArray(32).let {
            SecureRandom().nextBytes(it); android.util.Base64.encodeToString(it, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }.also { sf.writeText(it) }
        nodesFile = File(data, "nodes.json"); settingsFile = File(data, "settings.json")
        runCatching { val a = JSONArray(nodesFile.readText()); nodes.value = (0 until a.length()).map { Node.fromJson(a.getJSONObject(it)) } }
        runCatching { settings.value = Settings.fromJson(JSONObject(settingsFile.readText())) }
        tagSeq = nodes.value.mapNotNull { it.tag.removePrefix("n").toIntOrNull() }.maxOrNull() ?: 0
    }

    fun log(msg: String) = _logs.update { (it + msg.trim()).takeLast(200) }

    @Synchronized fun save() {
        fun write(f: File, s: String) { val t = File(f.path + ".tmp"); t.writeText(s); t.renameTo(f) }
        write(nodesFile, JSONArray(nodes.value.map { it.toJson() }).toString())
        write(settingsFile, settings.value.toJson().toString(1))
    }

    fun updateSettings(f: (Settings) -> Settings) { settings.update(f); scope.launch { save() } }
    private fun nextTag() = "n%05d".format(++tagSeq)

    // ---------------------------------------------------------------- import
    fun importText(text: String) {
        val links = Parser.splitInput(text)
        if (links.isEmpty()) return log("Clipboard has no v2ray links")
        log("Importing ${links.size} link(s)…")
        scope.launch { importWork(links, null) }
    }

    private fun importWork(links: List<String>, sub: Int?) {
        data class P(val name: String, val proto: String, val ob: JSONObject, val link: String, val key: String)
        var bad = 0; var dup = 0
        val seen = HashSet<String>(); val parsed = mutableListOf<P>()
        for (link in links.distinct()) {
            val r = runCatching { Parser.parseLink(link) }.getOrElse { bad++; log("skip: ${link.take(48)}…  (${it.message})"); null } ?: continue
            val k = Parser.nodeKey(r.ob)
            if (!seen.add(k)) { dup++; continue }
            parsed += P(r.name, r.proto, r.ob, link, k)
        }
        val existing = nodes.value.map { Parser.nodeKey(it.ob) }.toSet()
        val fresh = parsed.filter { it.key !in existing }
        dup += parsed.size - fresh.size
        val ok = Config.validateMany(fresh.map { it.ob })
        val new = fresh.zip(ok).mapNotNull { (p, good) ->
            if (!good) { bad++; log("rejected by core: ${p.name}"); null } else p
        }
        // a subscription drops configs it no longer serves (only if the download gave us something)
        val keep = parsed.map { it.key }.toSet()
        var stale = 0; var added = listOf<Node>()
        nodes.update { cur ->
            var list = cur
            if (sub != null && parsed.isNotEmpty()) {
                val before = list.size
                list = list.filter { !(it.sub == sub && !it.fav && Parser.nodeKey(it.ob) !in keep) }
                stale = before - list.size
            }
            val known = list.map { Parser.nodeKey(it.ob) }.toMutableSet()
            added = new.filter { known.add(it.key) }.map { Node(nextTag(), it.name, it.proto, it.ob, it.link, sub) }
            list + added
        }
        save()
        var msg = "+${added.size} new · $dup duplicate · $bad bad"
        if (stale > 0) msg += " · $stale removed"
        log((if (sub != null) "Sub ${sub + 1}: " else "Imported: ") + msg)
        if (sub != null) subStatus.update { it.toMutableList().also { l -> l[sub] = msg } }
        if (added.isNotEmpty()) scanNodes(added)
        if ((added.isNotEmpty() || stale > 0) && state.value == State.ON) log("Changes join Auto after you reconnect")
    }

    fun updateSub(i: Int) {
        val url = settings.value.subs[i].trim()
        if (url.isEmpty()) return log("Sub ${i + 1}: link is empty")
        if (i in busySubs.value) return
        busySubs.update { it + i }
        subStatus.update { it.toMutableList().also { l -> l[i] = "downloading…" } }
        val port = if (state.value == State.ON) settings.value.port else null
        scope.launch {
            try {
                val links = Parser.splitInput(Config.fetchSubscription(url, port))
                if (links.isEmpty()) {
                    subStatus.update { it.toMutableList().also { l -> l[i] = "✖ no v2ray links in this subscription" } }
                } else {
                    log("Sub ${i + 1}: got ${links.size} link(s)"); importWork(links, i)
                }
            } catch (e: Exception) {
                subStatus.update { it.toMutableList().also { l -> l[i] = "✖ ${e.message}" } }
            } finally { busySubs.update { it - i } }
        }
    }

    fun toggleFav(tag: String) { nodes.update { l -> l.map { if (it.tag == tag) it.copy(fav = !it.fav) else it } }; scope.launch { save() } }
    fun delete(tag: String) { nodes.update { l -> l.filter { it.tag != tag } }; scope.launch { save() } }
    fun removeDead() { nodes.update { l -> l.filter { it.fav || !it.tested || it.alive } }; scope.launch { save() } }
    fun clearAll() { nodes.update { l -> l.filter { it.fav } }; scope.launch { save() } }

    // ---------------------------------------------------------------- scan
    fun scanAll() = scanNodes(nodes.value)
    fun stopScan() { scanJob?.cancel() }

    fun scanNodes(target: List<Node>) {
        if (target.isEmpty() || scanJob?.isActive == true) return
        scanJob = scope.launch {
            scan.value = 0 to target.size
            val useLive = state.value == State.ON && target.all { it.tag in liveTags }
            var temp: BoxService? = null
            val api = if (useLive) clash else Clash(SCAN_API)
            try {
                if (!useLive) {
                    val cfg = Config.build(target, null, settings.value, vpn = false, port = SCAN_PORT, api = SCAN_API)
                    temp = Libbox.newService(cfg.toString(), Platform(app, null)).also { it.start() }
                    var tries = 0
                    while (!api.alive() && tries++ < 50) delay(100)
                }
                val sem = Semaphore(WORKERS); var done = 0
                target.map { n ->
                    async {
                        sem.withPermit {
                            if (!isActive) return@withPermit
                            val d = api.delay(n.tag) ?: api.delay(n.tag)
                            if (!isActive) return@withPermit
                            val now = System.currentTimeMillis()
                            nodes.update { l -> l.map { if (it.tag == n.tag) it.copy(delay = d ?: 0, ts = now) else it } }
                            synchronized(this@Core) { done++ }
                            scan.value = done to target.size
                        }
                    }
                }.awaitAll()
                val alive = nodes.value.count { it.alive }
                log("Scan done: $alive alive of ${nodes.value.size}")
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) log("Scan failed: ${e.message}")
            } finally {
                runCatching { temp?.close() }
                scan.value = null
                save()
            }
        }
        scanJob?.invokeOnCompletion { cause ->
            if (connectAfterScan) { connectAfterScan = false; if (cause == null) connect() }
        }
    }

    // ---------------------------------------------------------------- connect
    /** Call after VpnService.prepare() succeeded. */
    fun connect() {
        val list = nodes.value
        if (list.isEmpty()) return log("Paste some configs first")
        if (scanJob?.isActive == true) return log("Busy, wait for the scan to finish")
        if (list.none { it.alive }) {
            connectAfterScan = true; log("No scan results yet, scanning first…"); return scanAll()
        }
        val now = System.currentTimeMillis()
        val cand = list.filter { it.alive }.sortedBy { it.delay }.take(RETEST_TOP)
        if (retested || state.value != State.OFF) retested = false     // reconnects stay instant
        else if (cand.any { now - it.ts > SCAN_STALE_MS }) {
            retested = true; connectAfterScan = true
            log("Saved results are old, re-testing the best ${cand.size} configs first…")
            return scanNodes(cand)
        }
        val s = settings.value
        val pool = list.filter { it.alive }.sortedBy { it.delay }.take(20).map { it.tag }
        var cfg = Config.build(list, pool, s, vpn = true)
        if (s.bypassIr && cfg.getJSONObject("route").has("rule_set")) {
            // a broken rule-set file must not block connecting: fall back to .ir only
            if (runCatching { Libbox.checkConfig(cfg.toString()) }.isFailure) {
                log("Iran list failed to load, using .ir only")
                cfg = Config.build(list, pool, s, vpn = true, useRulesets = false)
            }
        }
        liveTags = list.map { it.tag }.toSet()
        pendingConfig = cfg.toString()
        _state.value = State.CONNECTING
        ContextCompat.startForegroundService(app, Intent(app, BoxVpnService::class.java))
        if (s.bypassIr && !Config.irReady()) scope.launch {
            delay(3000); updateIrRules(reconnect = true)
        }
    }

    fun disconnect() {
        monitorJob?.cancel()
        app.startService(Intent(app, BoxVpnService::class.java).setAction(BoxVpnService.ACTION_STOP))
        _state.value = State.OFF
    }

    fun onCoreStarted(err: String?) {
        if (err != null) {
            _state.value = State.OFF
            val tail = runCatching { Config.log.readText().takeLast(800) }.getOrDefault("")
            log("Connect failed: $err\n$tail"); return
        }
        _state.value = State.ON
        log("Connected")
        startMonitor()
    }

    fun onCoreStopped() { monitorJob?.cancel(); _state.value = State.OFF; _live.value = Live() }

    fun select(tag: String?) = scope.launch {
        runCatching { clash.select(tag ?: "auto") }.onFailure { log("Switch failed: ${it.message}") }
        _live.update { it.copy(history = emptyList()) }
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            launch {   // traffic stream
                while (isActive) {
                    runCatching { clash.streamTraffic({ !isActive }) { up, down -> _live.update { it.copy(up = up, down = down) } } }
                    delay(1000)
                }
            }
            val hist = ArrayDeque<Int?>()
            while (isActive) {
                val (tag, auto) = clash.current()
                val d = tag?.let { clash.delay(it) }
                if (tag != null && d != null) {
                    val now = System.currentTimeMillis()
                    nodes.update { l -> l.map { if (it.tag == tag) it.copy(delay = d, ts = now) else it } }
                }
                hist.addLast(d); while (hist.size > 36) hist.removeFirst()
                _live.update { it.copy(tag = tag, isAuto = auto, delay = d, history = hist.toList()) }
                delay(5000)
            }
        }
    }

    // ---------------------------------------------------------------- Iran rules
    fun updateIrRules(reconnect: Boolean = false) = scope.launch {
        log("Downloading Iran bypass lists…")
        try {
            Config.downloadIrRules(if (state.value == State.ON) settings.value.port else null)
            log("Iran lists updated")
            if (reconnect && state.value == State.ON) { retested = true; connect() }
        } catch (e: Exception) { log("Iran lists failed: ${e.message}") }
    }
}
