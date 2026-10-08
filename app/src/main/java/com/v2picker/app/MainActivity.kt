package com.v2picker.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Bg = Brush.linearGradient(listOf(Color(0xFF0E1020), Color(0xFF141530), Color(0xFF1D1236)))
private val Accent = Color(0xFF7C6CFF)
private val Cyan = Color(0xFF00C6FF)
private val Sub = Color(0xFF8F97B3)
private val CardBg = Color(0x0DFFFFFF)
private val CardBorder = Color(0x1AFFFFFF)

fun delayColor(d: Int?) = when {
    d == null || d <= 0 -> Color(0xFFFF5C7A); d < 300 -> Color(0xFF3DDC97); d < 800 -> Color(0xFFFFC857); else -> Color(0xFFFF8A5C)
}

fun fmtSpeed(b0: Long): String {
    var b = b0.toDouble()
    for (u in listOf("B/s", "KB/s", "MB/s", "GB/s")) { if (b < 1024) return if (u == "B/s") "%.0f %s".format(b, u) else "%.1f %s".format(b, u); b /= 1024 }
    return "%.1f TB/s".format(b)
}

class MainActivity : ComponentActivity() {

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(this) == null) Core.connect() else Core.log("VPN permission denied")
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun toggle() {
        if (Core.state.value != State.OFF) return Core.disconnect()
        val i = VpnService.prepare(this)
        if (i != null) vpnPermission.launch(i) else Core.connect()
    }

    fun clip(): String = (getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this) ?: "").toString()
    fun copy(t: String) = getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", t))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondary = Cyan, surface = Color(0xFF1A1C2E), background = Color(0xFF0E1020))) {
                Box(Modifier.fillMaxSize().background(Bg)) { Screen(this@MainActivity) }
            }
        }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg)
        .border(1.dp, CardBorder, RoundedCornerShape(16.dp)).padding(14.dp)) {
        if (title != null) Text(title, color = Sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
fun Screen(act: MainActivity) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color(0xCC0E1020)) {
                listOf(Icons.Default.Power to "Connect", Icons.Default.List to "Configs", Icons.Default.Settings to "Settings", Icons.Default.Notes to "Log")
                    .forEachIndexed { i, (ic, label) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, null) }, label = { Text(label) })
                    }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).padding(horizontal = 14.dp)) {
            when (tab) { 0 -> HomeTab(act); 1 -> ConfigsTab(act); 2 -> SettingsTab(act); else -> LogTab() }
        }
    }
}

@Composable
fun HomeTab(act: MainActivity) {
    val state by Core.state.collectAsStateWithLifecycle()
    val live by Core.live.collectAsStateWithLifecycle()
    val nodes by Core.nodes.collectAsStateWithLifecycle()
    val scan by Core.scan.collectAsStateWithLifecycle()
    val node = nodes.firstOrNull { it.tag == live.tag }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("V2Picker", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("${nodes.size} configs · ${nodes.count { it.alive }} alive", color = Sub, fontSize = 12.sp)
        Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
            val on = state != State.OFF
            Box(Modifier.size(170.dp).clip(CircleShape)
                .background(Brush.linearGradient(if (on) listOf(Color(0xFFFF4D79), Color(0xFFFF9A3D)) else listOf(Accent, Cyan)))
                .clickable { act.toggle() }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(56.dp))
                    Text(when (state) { State.OFF -> "CONNECT"; State.CONNECTING -> "CONNECTING…"; State.ON -> "DISCONNECT" }, fontWeight = FontWeight.Bold)
                }
            }
        }
        scan?.let { (d, t) ->
            GlassCard(title = "Scanning $d / $t") {
                LinearProgressIndicator(progress = { if (t == 0) 0f else d.toFloat() / t }, Modifier.fillMaxWidth())
                TextButton(onClick = { Core.stopScan() }) { Text("Stop") }
            }
        }
        GlassCard(title = "Active node") {
            Text(node?.name ?: if (state == State.ON) "…" else "Not connected", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((node?.proto ?: "").uppercase() + if (live.isAuto && node != null) " · AUTO" else "", color = Sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (!live.isAuto) TextButton(onClick = { Core.select(null) }) { Text("Back to Auto") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCard(Modifier.weight(1f), "Delay") {
                Text(live.delay?.let { "$it ms" } ?: if (state == State.ON) "timeout" else "-", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = delayColor(live.delay))
                val good = live.history.filterNotNull()
                if (good.isNotEmpty()) {
                    val loss = 100 * (live.history.size - good.size) / live.history.size
                    Text("min ${good.min()} · avg ${good.sum() / good.size} · max ${good.max()}\nloss $loss%", color = Sub, fontSize = 11.sp)
                }
                Sparkline(live.history)
            }
            GlassCard(Modifier.weight(1f), "Traffic") {
                Text("↑ ${fmtSpeed(live.up)}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Text("↓ ${fmtSpeed(live.down)}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun Sparkline(vals: List<Int?>) {
    Canvas(Modifier.fillMaxWidth().height(40.dp).padding(top = 6.dp)) {
        val good = vals.filterNotNull(); if (vals.size < 2 || good.isEmpty()) return@Canvas
        val mx = (good.max() * 1.2f).coerceAtLeast(100f)
        val step = size.width / 35f
        val p = Path(); var started = false
        vals.forEachIndexed { i, v ->
            val x = i * step
            if (v == null) { drawCircle(Color(0xFFFF5C7A), 3f, Offset(x, size.height - 2)); started = false; return@forEachIndexed }
            val y = size.height - (v / mx) * size.height
            if (!started) { p.moveTo(x, y); started = true } else p.lineTo(x, y)
        }
        drawPath(p, Accent, style = Stroke(3f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConfigsTab(act: MainActivity) {
    val nodes by Core.nodes.collectAsStateWithLifecycle()
    val live by Core.live.collectAsStateWithLifecycle()
    val state by Core.state.collectAsStateWithLifecycle()
    val scan by Core.scan.collectAsStateWithLifecycle()
    var favOnly by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<Node?>(null) }
    val shown = remember(nodes, favOnly, q) {
        nodes.filter { (!favOnly || it.fav) && (q.isBlank() || it.name.contains(q, true) || it.proto.contains(q, true)) }
            .sortedWith(compareBy<Node>({ !it.alive }, { if (it.alive) it.delay else Int.MAX_VALUE }, { !it.tested }))
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Button(onClick = { Core.importText(act.clip()) }) { Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(4.dp)); Text("Paste") }
            if (scan == null) OutlinedButton(onClick = { Core.scanAll() }) { Text("Scan all") }
            else OutlinedButton(onClick = { Core.stopScan() }) { Text("Stop ${scan!!.first}/${scan!!.second}") }
            OutlinedButton(onClick = { Core.removeDead() }) { Text("Remove dead") }
            OutlinedButton(onClick = { Core.clearAll() }) { Text("Clear") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Search") }, singleLine = true, shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.width(8.dp))
            FilterChip(favOnly, { favOnly = !favOnly }, label = { Text("★ Favs") })
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.tag }) { n ->
                val active = n.tag == live.tag
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (active) Accent.copy(alpha = .3f) else CardBg)
                    .combinedClickable(
                        onClick = { if (state == State.ON) Core.select(n.tag) else Core.log("Connect first, then tap a config to switch to it") },
                        onLongClick = { menu = n })
                    .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (n.fav) "★ " else "") + n.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(n.proto.uppercase() + (n.sub?.let { " · sub ${it + 1}" } ?: ""), color = Sub, fontSize = 11.sp)
                    }
                    Text(if (!n.tested) "-" else if (n.alive) "${n.delay} ms" else "dead", color = if (n.tested) delayColor(n.delay) else Sub, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    menu?.let { n ->
        AlertDialog(onDismissRequest = { menu = null }, title = { Text(n.name, maxLines = 2) }, confirmButton = {}, text = {
            Column {
                TextButton(onClick = { Core.toggleFav(n.tag); menu = null }) { Text(if (n.fav) "Unfavorite" else "★ Favorite") }
                TextButton(onClick = { act.copy(n.link); menu = null }) { Text("Copy link") }
                TextButton(onClick = { Core.scanNodes(listOf(n)); menu = null }) { Text("Test delay") }
                TextButton(onClick = { Core.delete(n.tag); menu = null }) { Text("Delete", color = Color(0xFFFF5C7A)) }
            }
        })
    }
}

@Composable
fun SettingsTab(act: MainActivity) {
    val s by Core.settings.collectAsStateWithLifecycle()
    val subStatus by Core.subStatus.collectAsStateWithLifecycle()
    val busy by Core.busySubs.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(8.dp))
        GlassCard(title = "Subscriptions") {
            for (i in 0 until SUB_SLOTS) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(s.subs[i], { v -> Core.updateSettings { it.copy(subs = it.subs.toMutableList().also { l -> l[i] = v }) } },
                        Modifier.weight(1f), placeholder = { Text("Sub ${i + 1} link") }, singleLine = true, shape = RoundedCornerShape(10.dp))
                    IconButton(onClick = { Core.updateSub(i) }, enabled = i !in busy) { Icon(Icons.Default.Refresh, "Update") }
                }
                if (subStatus[i].isNotEmpty()) Text(subStatus[i], color = Sub, fontSize = 11.sp, maxLines = 2)
            }
        }
        GlassCard(title = "Routing") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.mode == VpnMode.ALL, { Core.updateSettings { it.copy(mode = VpnMode.ALL) } }, label = { Text("Whole phone") })
                FilterChip(s.mode == VpnMode.APPS, { Core.updateSettings { it.copy(mode = VpnMode.APPS) } }, label = { Text("Only chosen apps") })
            }
            if (s.mode == VpnMode.APPS) {
                Text("${s.apps.size} app(s) selected", color = Sub, fontSize = 12.sp)
                OutlinedButton(onClick = { picker = true }) { Text("Choose apps") }
            }
            Toggle("Bypass Iran (.ir + Iranian IPs go direct)", s.bypassIr) { v -> Core.updateSettings { it.copy(bypassIr = v) } }
            val age = Config.irAgeDays()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (age == null) "Iran lists not downloaded (only .ir is bypassed)" else "Iran lists: %.0f day(s) old".format(age), color = Sub, fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { Core.updateIrRules(reconnect = Core.state.value == State.ON) }) { Text("Update") }
            }
            Toggle("Leak guard (DNS / WebRTC / IPv6)", s.leakGuard) { v -> Core.updateSettings { it.copy(leakGuard = v) } }
            Text("For a real kill switch: Android Settings → VPN → V2Picker → Always-on + Block connections without VPN", color = Sub, fontSize = 11.sp)
        }
        GlassCard(title = "Local proxy") {
            Toggle("Allow LAN (share proxy with other devices)", s.allowLan) { v -> Core.updateSettings { it.copy(allowLan = v) } }
            OutlinedTextField(s.port.toString(), { v -> v.toIntOrNull()?.takeIf { it in 1..65535 }?.let { p -> Core.updateSettings { it.copy(port = p) } } },
                label = { Text("Port") }, singleLine = true, shape = RoundedCornerShape(10.dp))
        }
        Text("Changes apply on next connect.", color = Sub, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
    }
    if (picker) AppPicker(act, s.apps) { picked -> picker = false; picked?.let { p -> Core.updateSettings { it.copy(apps = p) } } }
}

@Composable
fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp); Switch(value, onChange)
    }
}

@Composable
fun AppPicker(act: MainActivity, current: List<String>, done: (List<String>?) -> Unit) {
    val pm = act.packageManager
    val apps = remember {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != act.packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }
    val sel = remember { mutableStateListOf<String>().apply { addAll(current) } }
    var q by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { done(null) },
        confirmButton = { TextButton(onClick = { done(sel.toList()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { done(null) }) { Text("Cancel") } },
        title = { Text("Apps that use the VPN") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, placeholder = { Text("Search") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(apps.filter { q.isBlank() || it.second.contains(q, true) }, key = { it.first }) { (pkg, label) ->
                        Row(Modifier.fillMaxWidth().clickable { if (pkg in sel) sel.remove(pkg) else sel.add(pkg) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(pkg in sel, { if (it) sel.add(pkg) else sel.remove(pkg) })
                            Column { Text(label); Text(pkg, color = Sub, fontSize = 10.sp) }
                        }
                    }
                }
            }
        })
}

@Composable
fun LogTab() {
    val logs by Core.logs.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), reverseLayout = true) {
        items(logs.reversed()) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFCFD6FF), modifier = Modifier.padding(vertical = 2.dp)) }
    }
}
