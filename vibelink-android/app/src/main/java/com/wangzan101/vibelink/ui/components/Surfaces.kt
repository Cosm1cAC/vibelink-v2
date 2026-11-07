package com.wangzan101.vibelink.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.wangzan101.vibelink.ui.theme.VibeLinkColors
import com.wangzan101.vibelink.ui.theme.VibeLinkIcons
import com.wangzan101.vibelink.ui.theme.VibeLinkShapes
import com.wangzan101.vibelink.ui.theme.VibeLinkSize
import com.wangzan101.vibelink.ui.theme.VibeLinkTheme
import com.wangzan101.vibelink.ui.theme.VibeLinkType
import kotlinx.coroutines.delay

/** 页面底:干净的近白(深色下近黑),不加渐变。 */
@Composable
fun PageBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(VibeLinkTheme.colors.bg), content = content)
}

/** 卡片的底:白底 + 黑 5% 描边 + 极淡阴影;深色下只有描边。 */
fun Modifier.cardSurface(colors: VibeLinkColors, shape: Shape = VibeLinkShapes.Card): Modifier =
    (if (colors.cardShadow.alpha > 0f) {
        shadow(6.dp, shape, clip = false, ambientColor = colors.cardShadow.copy(alpha = 0.45f), spotColor = colors.cardShadow.copy(alpha = 0.16f))
    } else this)
        .background(colors.surface, shape)
        .border(0.5.dp, colors.edge, shape)

/** 悬浮元素的阴影(输入栏、浮在内容上的按钮)。 */
fun Modifier.floatShadow(colors: VibeLinkColors, shape: Shape): Modifier =
    shadow(14.dp, shape, clip = false, ambientColor = colors.floatShadow.copy(alpha = 0.3f), spotColor = colors.floatShadow.copy(alpha = 0.35f))

/** 一张卡片,内容裁在圆角里(行的按压效果不出角)。 */
@Composable
fun VibeLinkCard(modifier: Modifier = Modifier, shape: Shape = VibeLinkShapes.Card, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().cardSurface(VibeLinkTheme.colors, shape).clip(shape), content = content)
}

/**
 * 分组卡片:一组 [ListRow]。下面自带 12dp 间距,和下一组的 [GroupLabel](上 8)合起来是分组之间的 20。
 * 行与行的分隔线由行自己画,从文字处开始。
 */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    VibeLinkCard(modifier.padding(bottom = 12.dp), content = content)
}

/** 状态卡:左边圆形图标,右边标题和说明。用于页面顶上的总状态。 */
@Composable
fun StateCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tone: TileTone = TileTone.Neutral,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = VibeLinkTheme.colors
    VibeLinkCard(modifier.padding(bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val (bg, fg) = tone.colors(c)
            Box(Modifier.size(36.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = fg)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = VibeLinkType.BodyStrong, color = c.ink)
                if (!subtitle.isNullOrEmpty()) Text(subtitle, style = VibeLinkType.Caption, color = c.ink3)
            }
            trailing()
        }
    }
}

// ---------- 悬浮玻璃 ----------

/** 能做背景模糊(安卓 12 起有 RenderEffect);不能时玻璃退回不透明的卡片底。 */
val GlassBlurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 玻璃后面那层内容的画面:内容用 [glassSource] 画进来,[GlassBar] 取它模糊后垫在自己底下。 */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

/** 不支持模糊时为 null,[GlassBar] 就用不透明底。 */
@Composable
fun rememberGlassBackdrop(): GlassBackdrop? {
    if (!GlassBlurSupported) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** 把这块内容记成玻璃的背景。玻璃本身不能在这块内容里面。 */
fun Modifier.glassSource(backdrop: GlassBackdrop?, bgColor: Color = Color.Unspecified): Modifier =
    if (backdrop == null) this
    else onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record {
                if (bgColor != Color.Unspecified) {
                    drawRect(bgColor)
                }
                this@drawWithContent.drawContent()
            }
            drawLayer(backdrop.layer)
        }

private val GlassBlur = BlurEffect(30f, 30f, TileMode.Clamp)
private val AppleOrbBlur = BlurEffect(52f, 52f, TileMode.Clamp)

/**
 * 仅在用户手指主动滑动列表（且列表不在最底部）时才显示悬浮「回到底部」圆钮；
 * 停止滑动后短暂保留（约 1 秒供点击）即自动淡出隐藏，绝不因「高度不在最底部」而常驻。
 */
@Stable
class ScrollToBottomState internal constructor(
    internal val listState: LazyListState,
) {
    internal var userScrollTick by mutableIntStateOf(0)
    var visible by mutableStateOf(false)
        internal set

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && kotlin.math.abs(available.y) > 0.5f) {
                userScrollTick++
                if (listState.canScrollForward) {
                    visible = true
                }
            }
            return Offset.Zero
        }
    }

    fun hideImmediate() {
        visible = false
    }
}

@Composable
fun rememberScrollToBottomState(listState: LazyListState): ScrollToBottomState {
    val state = remember(listState) { ScrollToBottomState(listState) }
    val canForward by remember(listState) { derivedStateOf { listState.canScrollForward } }
    val isScrolling = listState.isScrollInProgress

    LaunchedEffect(canForward) {
        if (!canForward) {
            state.visible = false
        }
    }

    LaunchedEffect(state.userScrollTick, isScrolling, canForward) {
        if (!canForward || state.userScrollTick == 0) {
            state.visible = false
            return@LaunchedEffect
        }
        if (isScrolling) {
            state.visible = true
        } else if (state.visible) {
            delay(1000L)
            state.visible = false
        }
    }
    return state
}

/**
 * 苹果 iOS 液态毛玻璃（UltraThinMaterial Backdrop Blur）风格的「回到底部」圆形无文字悬浮按钮：
 * - 底层实时采集 [backdrop] 并施加高斯模糊（52px），彻底柔化底下透出的文字与气泡色彩；
 * - 叠加 iOS 毛玻璃半透光晕渐变与高光折射描边；
 * - 仅在用户滑动时淡入浮现，停止滑动后自动淡出。
 */
@Composable
fun BoxScope.GlassScrollToBottomButton(
    state: ScrollToBottomState,
    backdrop: GlassBackdrop?,
    hasMessages: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VibeLinkTheme.colors
    var origin by remember { mutableStateOf(Offset.Zero) }
    val show = hasMessages && state.visible && state.listState.canScrollForward

    AnimatedVisibility(
        visible = show,
        modifier = modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = 14.dp),
        enter = fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.82f),
        exit = fadeOut(tween(280)) + scaleOut(tween(280), targetScale = 0.82f),
    ) {
        val tintBrush = if (c.isDark) {
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFF383840).copy(alpha = 0.56f),
                    Color(0xFF1F1F24).copy(alpha = 0.44f),
                ),
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.56f),
                    Color(0xFFF0F2F7).copy(alpha = 0.40f),
                ),
            )
        }
        val rimBrush = Brush.linearGradient(
            colors = if (c.isDark) {
                listOf(
                    Color.White.copy(alpha = 0.36f),
                    Color.White.copy(alpha = 0.12f),
                    Color.Black.copy(alpha = 0.35f),
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.95f),
                    Color.White.copy(alpha = 0.45f),
                    Color.Black.copy(alpha = 0.10f),
                )
            },
        )

        Box(
            Modifier
                .size(40.dp)
                .shadow(
                    elevation = 10.dp,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.20f),
                    spotColor = Color.Black.copy(alpha = 0.26f),
                )
                .clip(CircleShape)
                .then(if (backdrop != null) Modifier.onGloballyPositioned { origin = it.positionInRoot() } else Modifier)
                .clickable {
                    state.hideImmediate()
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (backdrop != null) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            renderEffect = AppleOrbBlur
                            clip = true
                        }
                        .drawBehind {
                            // 读取列表滚动偏移以在每帧滑动时实时刷新毛玻璃折射画面
                            @Suppress("UNUSED_VARIABLE")
                            val idx = state.listState.firstVisibleItemIndex
                            @Suppress("UNUSED_VARIABLE")
                            val off = state.listState.firstVisibleItemScrollOffset
                            drawRect(c.bg)
                            val d = backdrop.origin - origin
                            translate(d.x, d.y) { drawLayer(backdrop.layer) }
                        },
                )
            }
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        brush = if (backdrop != null) tintBrush else Brush.linearGradient(listOf(c.glass, c.glass)),
                        shape = CircleShape,
                    )
                    .border(0.8.dp, rimBrush, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    VibeLinkIcons.ChevronDown,
                    contentDescription = "回到底部",
                    modifier = Modifier.size(18.dp),
                    tint = c.ink.copy(alpha = 0.78f),
                )
            }
        }
    }
}

/**
 * 玻璃输入栏的外壳:半透明底 + 背景模糊 + 悬浮阴影,高至少 52。
 * [backdrop] 为 null(不支持模糊、或后面没有内容)时用不透明底。
 */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
    shape: Shape = VibeLinkShapes.Pill,
    content: @Composable RowScope.() -> Unit,
) {
    val c = VibeLinkTheme.colors
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.floatShadow(c, shape).clip(shape)
            .then(if (backdrop != null) Modifier.onGloballyPositioned { origin = it.positionInRoot() } else Modifier),
    ) {
        if (backdrop != null) {
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { renderEffect = GlassBlur; clip = true }
                    .drawBehind {
                        val d = backdrop.origin - origin
                        translate(d.x, d.y) { drawLayer(backdrop.layer) }
                    },
            )
        }
        Row(
            Modifier.fillMaxWidth().background(if (backdrop != null) c.glass else c.surface)
                .border(0.5.dp, c.glassEdge, shape)
                .heightIn(min = VibeLinkSize.Composer).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}
