package com.zhiwei.qbot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Pink = Color(0xFFFF6AA8)
val Cyan = Color(0xFF63D8FF)
val BgTop = Color(0xFF151021)
val BgBottom = Color(0xFF0B0B10)
val CardBg = Color(0xFF171722)
val CardBg2 = Color(0xFF1E1E2B)
val Stroke = Color(0xFF2A2A3A)
val TextMain = Color(0xFFF2F1F7)
val TextDim = Color(0xFF9A9AAF)
val Green = Color(0xFF4ADE80)
val Amber = Color(0xFFFFC24B)
val Red = Color(0xFFFF6B6B)

@Composable
fun QbotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Pink,
            secondary = Cyan,
            background = BgBottom,
            surface = CardBg,
            surfaceVariant = CardBg2,
            onPrimary = Color.Black,
            onSecondary = Color.Black,
            onBackground = TextMain,
            onSurface = TextMain,
            outline = Stroke
        ),
        content = content
    )
}

/** 统一页面骨架：标题栏 + 可滚动内容 */
@Composable
fun Page(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    scroll: Boolean = true,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                TextButton(onClick = onBack) {
                    Text("‹", fontSize = 26.sp, color = Pink)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextMain)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, fontSize = 11.sp, color = TextDim)
                }
            }
            if (trailing != null) trailing()
        }
        if (scroll) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 28.dp)
            ) { content() }
        } else {
            Column(modifier = Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = TextDim,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp)
    )
}

@Composable
fun CardBox(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(18.dp),
        color = CardBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, Stroke),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(
        modifier = Modifier
            .width(size.dp)
            .height(size.dp)
            .background(color, CircleShape)
    )
}

@Composable
fun StatusPill(text: String, color: Color) {
    Row(
        modifier = Modifier
            .background(CardBg2, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Dot(color)
        Text(text, fontSize = 12.sp, color = color)
    }
}

@Composable
fun ToggleRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = TextMain)
            if (desc.isNotBlank()) {
                Text(desc, fontSize = 11.sp, color = TextDim, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Pink,
                uncheckedThumbColor = Color(0xFFB9B9C6),
                uncheckedTrackColor = Color(0xFF2C2C3A)
            )
        )
    }
}

@Composable
fun SpaceH(h: Int) {
    Spacer(modifier = Modifier.height(h.dp))
}