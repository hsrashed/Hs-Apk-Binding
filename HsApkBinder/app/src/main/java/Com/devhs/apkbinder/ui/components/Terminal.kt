package Com.devhs.apkbinder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import Com.devhs.apkbinder.data.LogLevel
import Com.devhs.apkbinder.data.LogLine
import Com.devhs.apkbinder.ui.theme.BgDeep
import Com.devhs.apkbinder.ui.theme.NeonTeal
import Com.devhs.apkbinder.ui.theme.TerminalAmber
import Com.devhs.apkbinder.ui.theme.TerminalGreen
import Com.devhs.apkbinder.ui.theme.TerminalRed
import Com.devhs.apkbinder.ui.theme.TerminalWhite
import Com.devhs.apkbinder.ui.theme.TextMuted

@Composable
fun TerminalConsole(logs: List<LogLine>, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) state.animateScrollToItem(logs.size - 1)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp, max = 240.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(BgDeep)
            .border(1.dp, NeonTeal.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "// LOG HISTORY",
                color = NeonTeal.copy(alpha = 0.7f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, null, tint = TextMuted, modifier = Modifier.size(16.dp))
            }
        }
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            items(logs) { line ->
                val color = when (line.level) {
                    LogLevel.INFO -> TerminalWhite
                    LogLevel.SUCCESS -> TerminalGreen
                    LogLevel.WARN -> TerminalAmber
                    LogLevel.ERROR -> TerminalRed
                }
                Text(
                    "[" + line.stamp + "] " + line.message,
                    color = color,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}
