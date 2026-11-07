package com.wangzan101.vibelink.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 字号与字重。只定大小、字重、行高,颜色由用的地方给。 */
object VibeLinkType {
    /** 大标题:空状态、欢迎页。 */
    val LargeTitle = TextStyle(fontSize = 28.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold)
    /** 页面标题:顶栏。 */
    val Title = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    /** 正文、行标题。 */
    val Body = TextStyle(fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    /** 卡片标题等需要强调的正文。 */
    val BodyStrong = TextStyle(fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    /** 说明文字。 */
    val Caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    /** 分组小标题。 */
    val Label = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    /** 提示、页脚。 */
    val Hint = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal)
    /** 等宽代码字体。 */
    val Mono = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal, fontFamily = FontFamily.Monospace)
    /** 行尾的值。 */
    val Value = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
}

/** 间距。页面左右留 [Page]。 */
object VibeLinkSpace {
    val S4 = 4.dp
    val S8 = 8.dp
    val S12 = 12.dp
    val S16 = 16.dp
    val S20 = 20.dp
    val S24 = 24.dp
    val Page = 16.dp
}

/** 各类元素的尺寸。 */
object VibeLinkSize {
    val TopBar = 56.dp
    val IconButton = 40.dp
    val RowMin = 52.dp
    val Button = 52.dp
    val Composer = 52.dp
    val Icon = 22.dp
    /** 列表行左边的图标块,里面的图形是 [TileIcon]。 */
    val Tile = 30.dp
    val TileIcon = 18.dp
    /** 行尾箭头等小图标。 */
    val SmallIcon = 16.dp
    const val IconStroke = 1.7f
}

/** 圆角。 */
object VibeLinkRadius {
    val Card = 16.dp
    val Bubble = 20.dp
    val BubbleTail = 6.dp
    val Button = 14.dp
    val Tile = 9.dp
}

/** 常用形状;顶层常量,重组时不新建。 */
object VibeLinkShapes {
    val Card = RoundedCornerShape(VibeLinkRadius.Card)
    /** 用户消息气泡:右下角是尾角。 */
    val UserBubble = RoundedCornerShape(VibeLinkRadius.Bubble, VibeLinkRadius.Bubble, VibeLinkRadius.BubbleTail, VibeLinkRadius.Bubble)
    /** VibeLink 的回答:左下角是尾角,和用户气泡对称。 */
    val AnswerBubble = RoundedCornerShape(VibeLinkRadius.Bubble, VibeLinkRadius.Bubble, VibeLinkRadius.Bubble, VibeLinkRadius.BubbleTail)
    val Button = RoundedCornerShape(VibeLinkRadius.Button)
    val Tile = RoundedCornerShape(VibeLinkRadius.Tile)
    val Pill = CircleShape
}
