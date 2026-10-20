package dev.m4s4t0.yawline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Numbers that do not jiggle as they change: tabular figures, heavy weight. */
fun numStyle(size: TextUnit, color: Color = Yaw.text, weight: FontWeight = FontWeight.Black) =
    TextStyle(fontSize = size, color = color, fontWeight = weight, fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp)

val LabelStyle get() = TextStyle(fontSize = 11.sp, color = Yaw.dim, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)

@Composable
fun Logo(size: TextUnit = 22.sp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("YAW", style = TextStyle(fontSize = size, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Yaw.text, letterSpacing = 2.sp))
        Text("LINE", style = TextStyle(fontSize = size, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Yaw.accent, letterSpacing = 2.sp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.width(18.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
            .background(Brush.horizontalGradient(listOf(Yaw.accent, Yaw.accent2))))
    }
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = 14.dp,
    border: Color = Yaw.border,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Yaw.panel)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 14.dp).background(Yaw.accent))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), style = LabelStyle.copy(color = Yaw.text, fontSize = 12.sp), modifier = Modifier.weight(1f))
        trailing()
    }
}

/** A small status capsule: a coloured dot and a word or two. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Yaw.mix(Yaw.panel, color, 0.14f))
            .border(1.dp, Yaw.mix(Yaw.border, color, 0.5f), CircleShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = TextStyle(fontSize = 11.sp, color = Yaw.text, fontWeight = FontWeight.SemiBold))
    }
}

/** A chunky button for use with gloves on and the car moving. */
@Composable
fun BigButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Yaw.accent,
    filled: Boolean = true,
    height: Dp = 52.dp,
    onClick: () -> Unit,
) {
    val fg = if (filled) (if (Yaw.palette.light) Color.White else Color.Black) else color
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(if (filled) color else Yaw.mix(Yaw.panel, color, 0.1f))
            .border(1.dp, if (filled) color else Yaw.mix(Yaw.border, color, 0.6f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            if (text.isNotEmpty()) Spacer(Modifier.width(8.dp))
        }
        if (text.isNotEmpty()) Text(text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = fg, letterSpacing = 0.6.sp), textAlign = TextAlign.Center)
    }
}

/** A labelled number with its unit, as on the dash and the replay readouts. */
@Composable
fun Readout(label: String, value: String, unit: String, modifier: Modifier = Modifier, color: Color = Yaw.text, size: TextUnit = 26.sp) {
    Column(modifier) {
        Text(label.uppercase(), style = LabelStyle)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = numStyle(size, color))
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = TextStyle(fontSize = 12.sp, color = Yaw.dim, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TextStyle(fontSize = 13.sp, color = Yaw.dim, lineHeight = 18.sp), modifier = modifier)
}

@Composable
fun ChipRow(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Text(
                o,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (on) (if (Yaw.palette.light) Color.White else Color.Black) else Yaw.text),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (on) Yaw.accent else Yaw.panelAlt)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}
