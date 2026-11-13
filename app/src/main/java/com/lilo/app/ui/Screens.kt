package com.lilo.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilo.app.GuardHub
import com.lilo.app.GuardService
import com.lilo.app.Profile
import com.lilo.app.Units
import com.lilo.app.logic.GuardState
import kotlinx.coroutines.delay
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

// ---------------------------------------------------------------- root: one main screen + settings

@Composable
fun LiloRoot(onRequestPermissions: () -> Unit) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    Scaffold(containerColor = Bg) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (showSettings) SettingsScreen(onBack = { showSettings = false })
            else HomeScreen(onRequestPermissions, onOpenSettings = { showSettings = true })
        }
    }
}

private fun hasLocation(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

// ---------------------------------------------------------------- home

/**
 * Everything a parent needs in one glance: the status ring, the start/stop button and "find child".
 * Radius and signal-source controls live in a pop-up sheet; profile and units live behind the gear icon.
 */
@Composable
fun HomeScreen(onRequestPermissions: () -> Unit, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val running by GuardHub.running.collectAsStateWithLifecycle()
    val snap by GuardHub.snapshot.collectAsStateWithLifecycle()
    val st by GuardHub.settings.collectAsStateWithLifecycle()
    val alarm by GuardHub.alarmActive.collectAsStateWithLifecycle()
    val sos by GuardHub.sosActive.collectAsStateWithLifecycle()
    val tag by GuardHub.espStatus.collectAsStateWithLifecycle()
    val profile by GuardHub.profile.collectAsStateWithLifecycle()

    var showControls by rememberSaveable { mutableStateOf(false) }
    var pinging by remember { mutableStateOf(false) }
    LaunchedEffect(pinging) {
        if (pinging) {
            delay(3000)
            pinging = false
        }
    }

    val child = profile.childName.ifBlank { "Your child" }
    val waiting = running && snap.smoothed == null && snap.state != GuardState.LOST

    val color = when {
        !running || waiting -> OffGrey
        snap.state == GuardState.SAFE -> SafeGreen
        snap.state == GuardState.FAR -> FarAmber
        else -> LostRed
    }
    val label = when {
        !running -> "OFF"
        waiting -> "..."
        else -> snap.state.name
    }
    val headline = when {
        !running -> "Guard is off"
        waiting -> "Waiting for signal…"
        snap.state == GuardState.SAFE -> "$child is safe"
        snap.state == GuardState.FAR -> "$child is getting far"
        else -> "$child is out of range!"
    }
    val fraction = snap.smoothed?.let { ((it + 100) / 70f).coerceIn(0.04f, 1f) } ?: 0f

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // top bar: name + settings gear
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Lilo", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
            }
        }

        // The ring grows on tall screens and the whole group is centred, so nothing sits stranded at the bottom.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val ringSize = minOf(maxWidth - 24.dp, maxHeight * 0.38f).coerceIn(200.dp, 300.dp)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            if (alarm) {
                Button(
                    onClick = { GuardHub.dismissAlarm(ctx) },
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LostRed, contentColor = Color.White),
                ) { Text("I FOUND THEM", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            }
            if (sos) {
                Button(
                    onClick = { GuardHub.dismissSos(ctx) },
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LostRed, contentColor = Color.White),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("SOS FROM ${child.uppercase()}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Tap to dismiss", fontSize = 13.sp)
                    }
                }
            }

            StatusRing(color, fraction, label, Units.signalLabel(snap.smoothed, st.useMeters), pulse = running && snap.state == GuardState.LOST, diameter = ringSize)
            Text(headline, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = color, textAlign = TextAlign.Center)

            // small status pills: live signal + source, and the tag when it is connected
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill {
                    SignalBars(snap.smoothed, color, small = true)
                    Spacer(Modifier.width(8.dp))
                    Text(if (st.useWifi) "Wi-Fi" else "Simulation", fontSize = 13.sp)
                }
                tag?.let { t ->
                    Pill {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(SafeGreen))
                        Spacer(Modifier.width(8.dp))
                        Text(t.id.ifBlank { "GuardTag" }, fontSize = 13.sp)
                    }
                }
            }

            // the main action
            Button(
                onClick = {
                    if (running) { GuardHub.userStopped = true; GuardService.stop(ctx) }
                    else { GuardHub.userStopped = false; GuardService.start(ctx) }
                },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) LostRed else SafeGreen,
                    contentColor = if (running) Color.White else Color.Black,
                ),
            ) { Text(if (running) "Stop guard" else "Start guard", fontSize = 19.sp, fontWeight = FontWeight.Bold) }

            // secondary actions
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tag != null) {
                    OutlinedButton(
                        onClick = { GuardHub.pingTag(); pinging = true },
                        enabled = !pinging,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f).height(72.dp),
                    ) { Text(if (pinging) "Flashing the tag…" else "Find child", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
                Column(
                    Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(18.dp)).background(Card)
                        .clickable { showControls = true }.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Radius & signal", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Beyond ${Units.limitLabel(st.threshold, st.useMeters)} · ${if (st.useWifi) "Wi-Fi" else "Simulation"}",
                        fontSize = 12.sp, color = MaterialTheme3.muted, maxLines = 2,
                    )
                }
            }
        }
        }
    }

    if (showControls) ControlsSheet(onDismiss = { showControls = false }, onRequestPermissions = onRequestPermissions)
}

// ---------------------------------------------------------------- controls pop-up

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlsSheet(onDismiss: () -> Unit, onRequestPermissions: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Card,
        contentColor = Color.White,
    ) {
        ControlsContent(onRequestPermissions)
    }
}

@Composable
private fun ControlsContent(onRequestPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val running by GuardHub.running.collectAsStateWithLifecycle()
    val snap by GuardHub.snapshot.collectAsStateWithLifecycle()
    val st by GuardHub.settings.collectAsStateWithLifecycle()
    val ssid by GuardHub.currentSsid.collectAsStateWithLifecycle()
    val linked by GuardHub.wifiLinked.collectAsStateWithLifecycle()

    var locationOk by remember { mutableStateOf(hasLocation(ctx)) }
    var noteFor by remember { mutableStateOf<Int?>(null) } // limit value that "Set limit here" just saved
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) locationOk = hasLocation(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val sliderColors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Track)

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Radius & signal", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        // ---- radius
        SubHeading("How far is too far")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Near" to -60, "Medium" to -70, "Far" to -80).forEach { (name, v) ->
                RadiusChip(
                    name = name,
                    value = Units.limitLabel(v, st.useMeters),
                    selected = st.threshold == v,
                    onClick = { GuardHub.setThreshold(v) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (st.useMeters) {
            val limitM = Units.dbmToMeters(st.threshold)
            Text("Warn me when farther than ${limitM.roundToInt()} m", color = MaterialTheme3.muted)
            Slider(
                value = metersToPos(limitM),
                onValueChange = { GuardHub.setThreshold(Units.metersToDbm(posToMeters(it))) },
                valueRange = 0f..1f,
                colors = sliderColors,
            )
        } else {
            Text("Limit: ${st.threshold} dBm   (lower = bigger radius)", color = MaterialTheme3.muted)
            Slider(
                value = st.threshold.toFloat(),
                onValueChange = { GuardHub.setThreshold(it.roundToInt()) },
                valueRange = -100f..-40f,
                colors = sliderColors,
            )
        }
        Button(
            onClick = {
                GuardHub.setLimitHere()
                noteFor = GuardHub.settings.value.threshold
            },
            enabled = snap.smoothed != null,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Set limit here", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        if (noteFor != null && noteFor == st.threshold) {
            Text("Limit set to ${Units.limitLabel(st.threshold, st.useMeters)} (the closest allowed is 3 m)", color = SafeGreen, fontSize = 13.sp)
        } else {
            Text("Put the tag at the farthest distance you accept, then tap.", color = MaterialTheme3.muted, fontSize = 13.sp)
        }
        if (st.useMeters) {
            Text("Distance is an estimate from signal strength. Walls and people change it.", color = MaterialTheme3.muted, fontSize = 12.sp)
        }

        // ---- source
        Spacer(Modifier.height(4.dp))
        SubHeading("Signal source")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(!st.useWifi, { GuardHub.setUseWifi(false) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Simulation") }
            SegmentedButton(st.useWifi, { GuardHub.setUseWifi(true) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Wi-Fi") }
        }
        if (st.useWifi) {
            Text(
                when {
                    !running -> "Guard is off. Tap Start guard to read the signal."
                    !linked -> "Not connected to a Wi-Fi network"
                    ssid != null -> "Connected to: $ssid"
                    else -> "Connected. Signal is being read."
                },
                color = if (running && !linked) FarAmber else Color.White,
            )
            if (running && linked && ssid == null) {
                Text(
                    "Android hides the network name while Location is off. Distance still works.",
                    color = MaterialTheme3.muted, fontSize = 13.sp,
                )
            }
            OutlinedTextField(
                value = st.ssidFilter,
                onValueChange = { GuardHub.setSsidFilter(it) },
                label = { Text("Only watch this network (blank = any)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!locationOk) {
                Text("Location permission is needed for Wi-Fi info.", color = FarAmber)
                OutlinedButton(onClick = onRequestPermissions) { Text("Grant permission") }
            }
            Text(
                "Connect to the tag's hotspot first (tap the NFC tag or join GuardTag-01). Turn Location on only if you want the network name or the filter above.",
                color = MaterialTheme3.muted, fontSize = 13.sp,
            )
        } else {
            if (st.useMeters) {
                val simM = Units.dbmToMeters(st.simRssi)
                Text("Simulated distance: ~${simM.roundToInt().coerceAtLeast(1)} m  (slide right to walk away)", color = MaterialTheme3.muted)
                Slider(
                    value = metersToPos(simM),
                    onValueChange = { GuardHub.setSimRssi(Units.metersToDbm(posToMeters(it))) },
                    valueRange = 0f..1f,
                    colors = sliderColors,
                )
            } else {
                Text("Simulated signal: ${st.simRssi} dBm", color = MaterialTheme3.muted)
                Slider(
                    value = st.simRssi.toFloat(),
                    onValueChange = { GuardHub.setSimRssi(it.roundToInt()) },
                    valueRange = -100f..-30f,
                    colors = sliderColors,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Drop link", Modifier.weight(1f), fontSize = 17.sp)
                Switch(checked = st.simDropped, onCheckedChange = { GuardHub.setSimDropped(it) })
            }
        }
    }
}

// ---------------------------------------------------------------- settings (gear icon): profile, units, pairing help

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val saved by GuardHub.profile.collectAsStateWithLifecycle()
    val st by GuardHub.settings.collectAsStateWithLifecycle()
    var guardian by remember(saved) { mutableStateOf(saved.guardianName) }
    var phone by remember(saved) { mutableStateOf(saved.guardianPhone) }
    var child by remember(saved) { mutableStateOf(saved.childName) }
    var justSaved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Profile") {
                OutlinedTextField(guardian, { guardian = it; justSaved = false }, label = { Text("Guardian name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it; justSaved = false }, label = { Text("Guardian phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(child, { child = it; justSaved = false }, label = { Text("Child name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = { GuardHub.saveProfile(Profile(guardian.trim(), phone.trim(), child.trim())); justSaved = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (justSaved) "Saved ✓" else "Save", fontSize = 17.sp) }
                Text("The tag's display shows the guardian name and phone if the child gets lost.", color = MaterialTheme3.muted, fontSize = 13.sp)
            }

            Section("Show signal as") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(st.useMeters, { GuardHub.setUseMeters(true) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Meters") }
                    SegmentedButton(!st.useMeters, { GuardHub.setUseMeters(false) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("dBm") }
                }
                Text("Meters is an estimate worked out from signal strength. dBm is the raw reading.", color = MaterialTheme3.muted, fontSize = 13.sp)
            }

            Section("Connect to the tag") {
                Text("Tap the NFC tag with your phone, or join the Wi-Fi network GuardTag-01 (password guard1234). Then open Lilo, choose Wi-Fi in Radius & signal, and start the guard.", color = MaterialTheme3.muted, fontSize = 14.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- small building blocks

private object MaterialTheme3 {
    val muted = Color(0xFFB4BAC4)
}

// Distance sliders use a log scale (1 m .. 40 m) so 2 m, 10 m and 30 m all land at visibly different spots.
private const val MAX_M = 40.0
private fun metersToPos(m: Double): Float = (ln(m.coerceIn(1.0, MAX_M)) / ln(MAX_M)).toFloat()
private fun posToMeters(p: Float): Double = MAX_M.pow(p.toDouble()).coerceIn(1.0, MAX_M)

@Composable
private fun SubHeading(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = OffGrey)
}

@Composable
private fun Pill(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Card).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One of the three equal-width radius presets: name on top, value below, same size every time. */
@Composable
private fun RadiusChip(name: String, value: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .height(64.dp)
            .clip(shape)
            .background(if (selected) Color(0xFF2F3640) else Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Color(0xFF4A515C), shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(name, fontSize = 12.sp, color = MaterialTheme3.muted, maxLines = 1)
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OffGrey)
        content()
    }
}

@Composable
private fun StatusRing(color: Color, fraction: Float, label: String, reading: String, pulse: Boolean, diameter: Dp = 232.dp) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val alpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (pulse) 0.3f else 1f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
        label = "alpha",
    )
    val sweep by animateFloatAsState(fraction * 360f, label = "sweep")
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 22.dp.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Track, -90f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            drawArc(color.copy(alpha = alpha), -90f, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 52.sp, fontWeight = FontWeight.Bold, color = color)
            Text(reading, fontSize = 22.sp, color = Color.White)
        }
    }
}

@Composable
private fun SignalBars(dbm: Int?, color: Color, small: Boolean = false) {
    val level = when {
        dbm == null -> 0
        dbm >= -55 -> 4
        dbm >= -65 -> 3
        dbm >= -75 -> 2
        dbm >= -85 -> 1
        else -> 0
    }
    val barWidth = if (small) 5.dp else 10.dp
    val base = if (small) 6 else 10
    val step = if (small) 4 else 9
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(if (small) 3.dp else 5.dp)) {
        repeat(4) { i ->
            Box(
                Modifier.width(barWidth).height((base + i * step).dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i < level) color else Track)
            )
        }
    }
}
