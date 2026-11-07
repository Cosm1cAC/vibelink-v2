package com.wangzan101.vibelink.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.vibelink.R
import com.wangzan101.vibelink.ui.theme.VibeLinkShapes
import com.wangzan101.vibelink.ui.theme.VibeLinkTheme
import com.wangzan101.vibelink.ui.theme.VibeLinkType
import java.io.ByteArrayOutputStream
import kotlin.math.min

data class AiAvatarPreset(
    val key: String,
    val modelName: String,
    val personaTitle: String,
    @DrawableRes val drawableRes: Int,
)

val AI_AVATAR_PRESETS: List<AiAvatarPreset> = listOf(
    AiAvatarPreset(
        key = "preset:gpt",
        modelName = "GPT",
        personaTitle = "白发龙角娘",
        drawableRes = R.drawable.avatar_ai_gpt,
    ),
    AiAvatarPreset(
        key = "preset:claude",
        modelName = "Claude",
        personaTitle = "橙发大小姐",
        drawableRes = R.drawable.avatar_ai_claude,
    ),
    AiAvatarPreset(
        key = "preset:gemini",
        modelName = "Gemini",
        personaTitle = "异瞳星猫娘",
        drawableRes = R.drawable.avatar_ai_gemini,
    ),
    AiAvatarPreset(
        key = "preset:grok",
        modelName = "Grok",
        personaTitle = "金发双马尾",
        drawableRes = R.drawable.avatar_ai_grok,
    ),
    AiAvatarPreset(
        key = "preset:glm",
        modelName = "GLM",
        personaTitle = "黑发猫耳Z娘",
        drawableRes = R.drawable.avatar_ai_glm,
    ),
    AiAvatarPreset(
        key = "preset:deepseek",
        modelName = "DeepSeek",
        personaTitle = "蓝色大肥鱼",
        drawableRes = R.drawable.avatar_ai_deepseek,
    ),
)

private val avatarBitmapCache = object : LruCache<String, ImageBitmap>(32) {}

fun decodeCustomAvatarBitmap(spec: String): ImageBitmap? {
    val clean = spec.trim()
    if (!clean.startsWith("data:image") && !clean.startsWith("base64:")) return null
    avatarBitmapCache.get(clean)?.let { return it }
    return runCatching {
        val b64 = clean.substringAfter("base64,", clean.removePrefix("base64:"))
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        bmp.asImageBitmap().also { avatarBitmapCache.put(clean, it) }
    }.getOrNull()
}

/**
 * 将相册选中的图片居中裁剪为正方形并缩放至 256×256，编码为 Base64 Data URL，便于本地持久化与跨端同步。
 */
fun encodeUriToAvatarDataUrl(context: Context, uri: Uri): String? =
    runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val edge = min(src.width, src.height).coerceAtLeast(1)
        val x = (src.width - edge) / 2
        val y = (src.height - edge) / 2
        val cropped = Bitmap.createBitmap(src, x, y, edge, edge)
        val scaled = Bitmap.createScaledBitmap(cropped, 256, 256, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 86, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        "data:image/jpeg;base64,$b64"
    }.getOrNull()

@DrawableRes
fun resolveAvatarDrawableRes(spec: String, idOrEngine: String, name: String): Int {
    when (spec.trim().lowercase()) {
        "preset:gpt", "gpt", "codex", "openai" -> return R.drawable.avatar_ai_gpt
        "preset:claude", "claude", "anthropic" -> return R.drawable.avatar_ai_claude
        "preset:gemini", "gemini", "antigravity", "google" -> return R.drawable.avatar_ai_gemini
        "preset:grok", "grok", "xai" -> return R.drawable.avatar_ai_grok
        "preset:glm", "glm", "opencode", "zhipu" -> return R.drawable.avatar_ai_glm
        "preset:deepseek", "deepseek", "vibelink" -> return R.drawable.avatar_ai_deepseek
    }
    val key = "${idOrEngine.lowercase()} ${name.lowercase()}"
    return when {
        "gemini" in key || "antigravity" in key || "bot_cto" in key || "bot_ui" in key -> R.drawable.avatar_ai_gemini
        "grok" in key || "xai" in key -> R.drawable.avatar_ai_grok
        "deepseek" in key || "vibelink" in key || "bot_sre" in key -> R.drawable.avatar_ai_deepseek
        "codex" in key || "gpt" in key || "bot_dev" in key -> R.drawable.avatar_ai_gpt
        "claude" in key || "bot_pm" in key -> R.drawable.avatar_ai_claude
        "opencode" in key || "glm" in key || "bot_qa" in key -> R.drawable.avatar_ai_glm
        else -> R.drawable.avatar_ai_deepseek
    }
}

/**
 * Bot / Agent 头像组件：
 * - 优先渲染用户从手机相册上传的自定义头像（`data:image/jpeg;base64,...`）
 * - 默认渲染 6 款 AI 拟人头像（Gemini 异瞳星猫娘 / Claude 橙发大小姐 / GPT 白发龙角娘 / GLM 黑发猫耳Z娘 / DeepSeek 干饭蓝鲸娘 / Grok 金发双马尾）
 */
@Composable
fun AgentAvatar(
    id: String,
    name: String,
    size: Dp = 52.dp,
    onClick: (() -> Unit)? = null,
    avatarOverride: String? = null,
    cornerRadius: Dp? = null,
) {
    val ctx = LocalContext.current
    val c = VibeLinkTheme.colors
    val avatarVer by AgentPrefs.avatarVersionFlow.collectAsState()
    val botVer by BotTeamStore.versionFlow.collectAsState()
    val bots by BotTeamStore.botsFlow.collectAsState()

    val bot = remember(id, botVer, bots) {
        bots.firstOrNull { it.id == id } ?: BotTeamStore.getBot(ctx, id)
    }
    val effectiveSpec = remember(id, avatarOverride, avatarVer, botVer, bot?.avatar) {
        avatarOverride?.takeIf { it.isNotBlank() }
            ?: bot?.avatar?.takeIf { it.isNotBlank() }
            ?: AgentPrefs.avatar(ctx, id).takeIf { it.isNotBlank() }
            ?: ""
    }
    val customBitmap = remember(effectiveSpec) {
        decodeCustomAvatarBitmap(effectiveSpec)
    }
    val presetDrawable = remember(effectiveSpec, id, bot?.engine, name) {
        resolveAvatarDrawableRes(effectiveSpec, bot?.engine ?: id, name)
    }
    val shape = RoundedCornerShape(cornerRadius ?: (size * 0.22f))

    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(Color.White)
            .border(0.5.dp, c.edge, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (customBitmap != null) {
            Image(
                bitmap = customBitmap,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                painter = painterResource(presetDrawable),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 可复用的 Bot 头像选择器组件（支持相册自定义上传 + 4 款 AI 拟人默认图一键切换）
 */
@Composable
fun BotAvatarPickerSection(
    currentAvatar: String,
    engine: String,
    name: String,
    onAvatarChange: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val c = VibeLinkTheme.colors
    var uploadHint by remember { mutableStateOf("") }
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            val encoded = encodeUriToAvatarDataUrl(ctx, uri)
            if (encoded != null) {
                onAvatarChange(encoded)
                uploadHint = "✅ 已从相册载入自定义头像"
            } else {
                uploadHint = "⚠️ 图片解析失败，请重试"
            }
        }
    }

    val effectiveSpec = currentAvatar.ifBlank { BotTeamStore.defaultAvatarForEngine(engine) }
    val isCustomImage = effectiveSpec.startsWith("data:image") || effectiveSpec.startsWith("base64:")

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.fill)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AgentAvatar(
                id = engine,
                name = name.ifBlank { BotTeamStore.defaultModelNameForEngine(engine) },
                size = 60.dp,
                avatarOverride = effectiveSpec,
                onClick = { galleryLauncher.launch("image/*") },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Bot 头像设置（默认绑定底层模型 AI 拟人图）",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.ink,
                )
                Text(
                    if (isCustomImage) "当前：自定义相册头像（点击下方可切换回 AI 拟人图）"
                    else "当前：AI 拟人默认图（随底层模型自动匹配，也可自定义）",
                    fontSize = 11.sp,
                    color = c.ink3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .clip(VibeLinkShapes.Pill)
                            .background(c.accent)
                            .clickable { galleryLauncher.launch("image/*") }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            "📷 从相册自定义头像",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.onAccent,
                        )
                    }
                    if (isCustomImage) {
                        Box(
                            Modifier
                                .clip(VibeLinkShapes.Pill)
                                .background(c.surface)
                                .border(0.5.dp, c.edge, VibeLinkShapes.Pill)
                                .clickable {
                                    onAvatarChange(BotTeamStore.defaultAvatarForEngine(engine))
                                    uploadHint = "已恢复底层模型默认 AI 拟人头像"
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                "🔄 恢复模型默认",
                                fontSize = 11.sp,
                                color = c.ink2,
                            )
                        }
                    }
                }
            }
        }

        if (uploadHint.isNotBlank()) {
            Text(uploadHint, fontSize = 11.sp, color = c.accent)
        }

        // 6 款 AI 拟人预设 3×2 网格快速切换栏（GPT / Claude / Gemini / Grok / GLM / DeepSeek）
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AI_AVATAR_PRESETS.chunked(3).forEach { rowPresets ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rowPresets.forEach { preset ->
                        val selected = !isCustomImage && effectiveSpec == preset.key
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) c.accent.copy(alpha = 0.15f) else c.surface)
                                .border(
                                    width = if (selected) 1.5.dp else 0.5.dp,
                                    color = if (selected) c.accent else c.edge,
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .clickable {
                                    onAvatarChange(preset.key)
                                    uploadHint = ""
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Image(
                                painter = painterResource(preset.drawableRes),
                                contentDescription = preset.modelName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                preset.modelName,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) c.accent else c.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                preset.personaTitle,
                                fontSize = 9.5.sp,
                                color = c.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 独立弹窗：在 Bot 资料页点击头像即可快速更换头像（相册上传或切换 AI 拟人预设）
 */
@Composable
fun AvatarQuickEditDialog(
    agentId: String,
    currentName: String,
    engine: String,
    initialAvatar: String,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var selectedAvatar by remember {
        mutableStateOf(
            initialAvatar.ifBlank {
                AgentPrefs.avatar(ctx, agentId).ifBlank { BotTeamStore.defaultAvatarForEngine(engine) }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("更换 $currentName 的头像", style = VibeLinkType.Title)
        },
        text = {
            BotAvatarPickerSection(
                currentAvatar = selectedAvatar,
                engine = engine,
                name = currentName,
                onAvatarChange = { selectedAvatar = it },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    AgentPrefs.setAvatar(ctx, agentId, selectedAvatar)
                    BotTeamStore.getBot(ctx, agentId)?.let { bot ->
                        BotTeamStore.upsertBot(ctx, bot.copy(avatar = selectedAvatar))
                    }
                    onSaved(selectedAvatar)
                },
            ) {
                Text("保存头像", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
