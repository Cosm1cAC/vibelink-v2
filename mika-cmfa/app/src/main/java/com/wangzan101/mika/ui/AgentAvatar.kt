package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlin.math.absoluteValue

/**
 * agent 头像：固定色 + 名称首字。QQ 那种圆角方形，点一下进资料页。
 * 颜色按 id 稳定取，同一台设备/同一个 agent 每次都是同一个色。
 * 以后要换成图片，只改这里。
 */
@Composable
fun AgentAvatar(
    id: String,
    name: String,
    size: Dp = 52.dp,
    onClick: (() -> Unit)? = null,
) {
    val c = MikaTheme.colors
    val palette = c.series + c.seriesOther
    val base = palette[id.hashCode().absoluteValue % palette.size]
    val glyph = name.trim().take(1).uppercase().ifBlank { "?" }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(Brush.linearGradient(listOf(base, base.copy(alpha = 0.74f))))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
