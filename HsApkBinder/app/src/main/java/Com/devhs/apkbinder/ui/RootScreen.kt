package Com.devhs.apkbinder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import Com.devhs.apkbinder.ui.home.HomeScreen
import Com.devhs.apkbinder.ui.settings.SettingsScreen
import Com.devhs.apkbinder.ui.theme.BgDeep
import Com.devhs.apkbinder.ui.theme.BgSurface
import Com.devhs.apkbinder.ui.theme.NeonTeal
import Com.devhs.apkbinder.ui.theme.TextMuted

@Composable
fun RootScreen() {
    var tab by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = BgDeep,
        bottomBar = {
            NavigationBar(containerColor = BgSurface, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, null) },
                    label = { Text("Home") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonTeal,
                        selectedTextColor = NeonTeal,
                        indicatorColor = NeonTeal.copy(alpha = 0.12f),
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    )
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonTeal,
                        selectedTextColor = NeonTeal,
                        indicatorColor = NeonTeal.copy(alpha = 0.12f),
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    )
                )
            }
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(BgDeep, BgSurface, BgDeep)))
        ) {
            if (tab == 0) HomeScreen() else SettingsScreen()
        }
    }
}
