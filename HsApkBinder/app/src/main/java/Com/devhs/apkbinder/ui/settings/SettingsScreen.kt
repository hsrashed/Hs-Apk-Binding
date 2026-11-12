package Com.devhs.apkbinder.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import Com.devhs.apkbinder.data.Prefs
import Com.devhs.apkbinder.ui.components.GlowCard
import Com.devhs.apkbinder.ui.components.NeonGradientButton
import Com.devhs.apkbinder.ui.theme.NeonTeal
import Com.devhs.apkbinder.ui.theme.TextMuted
import Com.devhs.apkbinder.ui.theme.TextPrimary
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val savedToken by Prefs.token(ctx).collectAsState(initial = "")
    val savedChat by Prefs.chatId(ctx).collectAsState(initial = "")
    val testMode by Prefs.testMode(ctx).collectAsState(initial = false)

    var token by remember { mutableStateOf("") }
    var chatId by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(savedToken, savedChat) {
        if (!loaded && (savedToken.isNotEmpty() || savedChat.isNotEmpty())) {
            token = savedToken
            chatId = savedChat
            loaded = true
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("CONFIGURATION", color = NeonTeal, fontSize = 20.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(4.dp))
        Text("Telegram bot credentials", color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))

        GlowCard {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; saved = false },
                label = { Text("Bot Token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = chatId,
                onValueChange = { chatId = it; saved = false },
                label = { Text("Chat ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = testMode,
                    onCheckedChange = { scope.launch { Prefs.setTestMode(ctx, it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = NeonTeal)
                )
                Spacer(Modifier.width(10.dp))
                Text("Test payload on this device", color = TextPrimary, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        NeonGradientButton(
            text = if (saved) "SAVED" else "SAVE",
            modifier = Modifier.fillMaxWidth()
        ) {
            scope.launch {
                Prefs.save(ctx, token.trim(), chatId.trim())
                saved = true
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Both fields stay fully editable and are stored locally.",
            color = TextMuted,
            fontSize = 11.sp
        )
    }
}
