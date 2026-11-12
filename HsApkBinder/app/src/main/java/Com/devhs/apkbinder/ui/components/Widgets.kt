package Com.devhs.apkbinder.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import Com.devhs.apkbinder.ui.theme.BgDeep
import Com.devhs.apkbinder.ui.theme.BgElevated
import Com.devhs.apkbinder.ui.theme.BgSurface
import Com.devhs.apkbinder.ui.theme.NeonBlue
import Com.devhs.apkbinder.ui.theme.NeonTeal
import Com.devhs.apkbinder.ui.theme.StrokeSoft
import Com.devhs.apkbinder.ui.theme.TextMuted
import Com.devhs.apkbinder.ui.theme.TextPrimary

@Composable
fun NeonGradientButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "glow")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "a"
    )
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .shadow(
                if (enabled) 22.dp else 0.dp,
                RoundedCornerShape(18.dp),
                ambientColor = NeonTeal,
                spotColor = NeonBlue
            )
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.horizontalGradient(
                    if (enabled) listOf(NeonTeal.copy(alpha = alpha), NeonBlue.copy(alpha = alpha))
                    else listOf(StrokeSoft, StrokeSoft)
                )
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 18.dp, horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) BgDeep else TextMuted,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun GlowCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .shadow(14.dp, RoundedCornerShape(20.dp), ambientColor = NeonTeal.copy(alpha = 0.35f))
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(BgElevated, BgSurface)))
            .border(BorderStroke(1.dp, NeonTeal.copy(alpha = 0.25f)), RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(18.dp),
        content = content
    )
}

@Composable
fun FeatureCheck(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (checked) NeonTeal.copy(alpha = 0.08f) else Color.Transparent)
            .border(
                BorderStroke(1.dp, if (checked) NeonTeal.copy(alpha = 0.45f) else StrokeSoft),
                RoundedCornerShape(14.dp)
            )
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = NeonTeal,
                uncheckedColor = TextMuted,
                checkmarkColor = BgDeep
            )
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (checked) NeonTeal else TextPrimary, fontSize = 14.sp)
    }
}
