package Com.devhs.apkbinder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import Com.devhs.apkbinder.ui.theme.*

@Composable
fun SettingsScreen(
    currentBotToken: String,
    currentChatId: String,
    onSaveConfig: (String, String) -> Unit
) {
    var botToken by remember { mutableStateOf(currentBotToken) }
    var chatId by remember { mutableStateOf(currentChatId) }
    var savedMessage by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Telegram Configuration",
            color = TealAccent,
            fontSize = 24.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "Bot Token", color = TextPrimary, fontSize = 14.sp)
                OutlinedTextField(
                    value = botToken,
                    onValueChange = { botToken = it },
                    placeholder = { Text("Enter Telegram Bot API Token...", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = CardBorder,
                        focusedContainerColor = DarkBackground,
                        unfocusedContainerColor = DarkBackground,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(text = "Chat ID", color = TextPrimary, fontSize = 14.sp)
                OutlinedTextField(
                    value = chatId,
                    onValueChange = { chatId = it },
                    placeholder = { Text("Enter Target Telegram Chat ID...", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = CardBorder,
                        focusedContainerColor = DarkBackground,
                        unfocusedContainerColor = DarkBackground,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            }
        }

        Button(
            onClick = {
                onSaveConfig(botToken, chatId)
                savedMessage = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(listOf(TealAccent, ElectricBlue))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Save Configuration",
                    color = DarkBackground,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        if (savedMessage) {
            Text(
                text = "[SUCCESS] Configuration saved successfully!",
                color = NeonGreen,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}
