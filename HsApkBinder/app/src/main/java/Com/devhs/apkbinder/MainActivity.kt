package Com.devhs.apkbinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import Com.devhs.apkbinder.ui.RootScreen
import Com.devhs.apkbinder.ui.theme.HsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HsTheme { RootScreen() } }
    }
}
