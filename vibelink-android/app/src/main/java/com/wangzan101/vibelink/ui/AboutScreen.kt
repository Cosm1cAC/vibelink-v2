package com.wangzan101.vibelink.ui

import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.wangzan101.vibelink.R
import com.wangzan101.vibelink.about.ApkUpdater
import com.wangzan101.vibelink.about.CheckStatus
import com.wangzan101.vibelink.about.VibeLinkLinks
import com.wangzan101.vibelink.about.QrImage
import com.wangzan101.vibelink.about.ReleaseInfo
import com.wangzan101.vibelink.about.UpdateState
import com.wangzan101.vibelink.ui.components.ButtonKind
import com.wangzan101.vibelink.ui.components.GroupCard
import com.wangzan101.vibelink.ui.components.ListRow
import com.wangzan101.vibelink.ui.components.VibeLinkButton
import com.wangzan101.vibelink.ui.components.PageScaffold
import com.wangzan101.vibelink.ui.components.RowChevron
import com.wangzan101.vibelink.ui.components.RowValue
import com.wangzan101.vibelink.ui.components.ValueTone
import com.wangzan101.vibelink.ui.theme.VibeLinkIcons
import com.wangzan101.vibelink.ui.theme.VibeLinkTheme
import com.wangzan101.vibelink.ui.theme.VibeLinkType

/** 关于页上点开的底部面板。 */
private enum class AboutSheet { Update, Changelog, Feedback, Group, Diagnostics }

/**
 * 关于 VibeLink:顶上图标、名字、一句话介绍、版本(Beta 标记、构建编号);下面三组——检查更新与更新记录,
 * 反馈、内测群、关注作者,开源仓库与使用说明;一段数据说明;最底下许可证与作者。
 * 外部链接都交给 [onOpenUrl](系统浏览器);手动检查的结果到了([UpdateState.manualRounds] 变了)才提示。
 * [diagnostics] 写出诊断信息(反馈面板里「复制诊断信息」点开预览时在后台线程调)。
 */
@Composable
fun AboutScreen(
    versionName: String,
    build: String,
    beta: Boolean,
    update: UpdateState,
    issueUrl: String,
    onCheck: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onSaveQr: () -> Boolean,
    onAskVibeLinkToJoin: () -> Unit,
    onOpenLicense: () -> Unit,
    onBack: () -> Unit,
    diagnostics: () -> String = { "" },
) {
    val c = VibeLinkTheme.colors
    val context = LocalContext.current
    var sheet by rememberSaveable { mutableStateOf<AboutSheet?>(null) }
    // 点了「检查更新」后等这一轮结果:有新版直接弹出说明,没有就提示一句
    var waitingRound by remember { mutableIntStateOf(-1) }
    LaunchedEffect(update.manualRounds) {
        if (waitingRound >= 0 && update.manualRounds > waitingRound) {
            waitingRound = -1
            when {
                update.update != null -> sheet = AboutSheet.Update
                update.status == CheckStatus.Failed ->
                    Toast.makeText(context, AboutCopy.OFFLINE_TOAST, Toast.LENGTH_SHORT).show()
                else -> Toast.makeText(context, AboutCopy.UP_TO_DATE_TOAST, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val saveQr = {
        Toast.makeText(context, if (onSaveQr()) AboutCopy.QR_SAVED else AboutCopy.QR_SAVE_FAILED, Toast.LENGTH_SHORT).show()
    }

    PageScaffold(AboutCopy.TITLE, onBack) {
        Hero(versionName, build, beta)

        GroupCard {
            val v = AboutCopy.checkValue(update)
            ListRow(
                VibeLinkIcons.Refresh, AboutCopy.CHECK, if (update.hasUpdate) AboutCopy.CHECK_SUB_NEW else AboutCopy.CHECK_SUB, first = true,
                onClick = {
                    if (update.hasUpdate) sheet = AboutSheet.Update
                    else if (update.status != CheckStatus.Checking) {
                        waitingRound = update.manualRounds
                        onCheck()
                    }
                },
            ) {
                TrailingValue(v)
                RowChevron()
            }
            ListRow(VibeLinkIcons.Lines, AboutCopy.CHANGELOG, AboutCopy.CHANGELOG_SUB, onClick = { sheet = AboutSheet.Changelog }) { RowChevron() }
        }
        GroupCard {
            ListRow(VibeLinkIcons.Bug, AboutCopy.FEEDBACK, AboutCopy.FEEDBACK_SUB, first = true, onClick = { sheet = AboutSheet.Feedback }) { RowChevron() }
        }
        GroupCard {
            ListRow(VibeLinkIcons.Code, AboutCopy.REPO, AboutCopy.REPO_SUB, first = true, onClick = { onOpenUrl(VibeLinkLinks.REPO) }) { ExternalMark() }
            ListRow(VibeLinkIcons.Book, AboutCopy.GUIDE, AboutCopy.GUIDE_SUB, onClick = { onOpenUrl(VibeLinkLinks.README) }) { ExternalMark() }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp).background(c.okSoft, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(VibeLinkIcons.Shield, null, Modifier.padding(top = 1.dp).size(18.dp), tint = c.ok)
            Text(AboutCopy.DATA_NOTE, style = VibeLinkType.Caption, color = c.ink2)
        }

        Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                buildAnnotatedString {
                    val (before, after) = stringResource(R.string.about_released_under).split("%1\$s").let { it[0] to it.getOrElse(1) { "" } }
                    append(before)
                    withStyle(SpanStyle(color = c.accent)) { append(AboutCopy.LICENSE_LINK) }
                    append(after)
                },
                Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpenLicense).padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("about-license"),
                style = VibeLinkType.Hint, color = c.ink3, textAlign = TextAlign.Center,
            )
            Text(AboutCopy.AUTHOR, Modifier.padding(top = 2.dp), style = VibeLinkType.Hint, color = c.ink3)
        }
    }

    val close = { sheet = null }
    when (sheet) {
        AboutSheet.Update -> update.update?.let { r ->
            UpdateSheet(r, versionName, beta, onOpenUrl = { close(); onOpenUrl(it) }, onDismiss = close)
        }
        AboutSheet.Changelog -> ChangelogSheet(update.latest, onOpenAll = { close(); onOpenUrl(VibeLinkLinks.RELEASES) }, onDismiss = close)
        AboutSheet.Feedback -> FeedbackSheet(
            onGitHub = { close(); onOpenUrl(issueUrl) },
            onUpstream = { close(); onOpenUrl(VibeLinkLinks.UPSTREAM) },
            onDiagnostics = { sheet = AboutSheet.Diagnostics },
            onDismiss = close,
        )
        AboutSheet.Diagnostics -> DiagnosticsSheet(diagnostics, onDismiss = close)
        AboutSheet.Group -> GroupSheet(onSave = saveQr, onAskVibeLink = { close(); onAskVibeLinkToJoin() }, onDismiss = close)
        null -> Unit
    }
}

@Composable
private fun Hero(versionName: String, build: String, beta: Boolean) {
    val c = VibeLinkTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painterResource(R.drawable.vibelink_launcher_art), contentDescription = null,
            Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)),
        )
        Text("VibeLink", Modifier.padding(top = 12.dp), style = VibeLinkType.Title, fontWeight = FontWeight.Bold, color = c.ink)
        Text(AboutCopy.TAGLINE, Modifier.padding(top = 4.dp), style = VibeLinkType.Caption, color = c.ink2, textAlign = TextAlign.Center)
        Row(Modifier.padding(top = 10.dp).testTag("about-version"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(AboutCopy.version(versionName), style = VibeLinkType.Hint, color = c.ink3)
            if (beta) {
                Text(
                    AboutCopy.BETA, Modifier.background(c.accentSoft, RoundedCornerShape(7.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
                    style = VibeLinkType.Hint, fontWeight = FontWeight.SemiBold, color = c.accent,
                )
            }
            Text("· ${AboutCopy.buildLabel(build)}", style = VibeLinkType.Hint, color = c.ink3)
        }
    }
}

/** 行尾的值:转圈、红点加文字,或一段普通的值。 */
@Composable
internal fun TrailingValue(v: AboutCopy.CheckValue) {
    if (v.spinner) {
        CircularProgressIndicator(Modifier.size(16.dp).testTag("update-spinner"), color = VibeLinkTheme.colors.accent, strokeWidth = 2.dp)
        return
    }
    if (v.dot) UpdateDot()
    v.text?.let { RowValue(it, if (v.highlight) ValueTone.Link else ValueTone.Plain) }
}

/** 有新版本时的小红点。 */
@Composable
internal fun UpdateDot(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(VibeLinkTheme.colors.bad, CircleShape).testTag("update-dot"))
}

@Composable
private fun ExternalMark() {
    Icon(VibeLinkIcons.External, null, Modifier.size(15.dp), tint = VibeLinkTheme.colors.ink3)
}

// ---------- 底部面板 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutSheetFrame(title: String, sub: String?, tag: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = VibeLinkTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        // 面板内容太高(矮屏、大字号)时整体能滚,底下的按钮总够得着
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp).testTag(tag),
        ) {
            Text(title, style = VibeLinkType.Title, color = c.ink)
            sub?.let { Text(it, Modifier.padding(top = 2.dp), style = VibeLinkType.Caption, color = c.ink3) }
            content()
        }
    }
}

/** 更新说明(Markdown),浅底、太长时在框里滚。 */
@Composable
private fun NotesBox(notes: String) {
    val c = VibeLinkTheme.colors
    Box(
        Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(max = 280.dp)
            .background(c.fill, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()).padding(12.dp),
    ) {
        MarkdownText(notes, VibeLinkType.Caption.copy(color = c.ink2))
    }
}

@Composable
private fun UpdateSheet(r: ReleaseInfo, current: String, beta: Boolean, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    // 应用内更新：下载走 App 自己，进度显示在按钮上，下完直接拉起系统安装器
    var progress by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AboutSheetFrame(AboutCopy.updateTitle(r.version), AboutCopy.updateSub(current, beta, r.date), "update-sheet", onDismiss) {
        if (r.notes.isNotBlank()) NotesBox(r.notes)
        error?.let {
            Text(it, Modifier.padding(top = 10.dp), style = VibeLinkType.Caption, color = VibeLinkTheme.colors.bad)
        }
        VibeLinkButton(
            progress?.let { "下载中 $it%" } ?: AboutCopy.UPDATE_DOWNLOAD,
            {
                if (progress == null) {
                    error = null
                    progress = 0
                    ApkUpdater.start(
                        ctx, r.downloadUrl, expected = r.version,
                        onProgress = { progress = it },
                        onError = { error = it; progress = null },
                        onReady = { progress = null },
                    )
                }
            },
            Modifier.fillMaxWidth().padding(top = 14.dp),
        )
        Text(
            "下载失败？用浏览器打开",
            Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp))
                .clickable { onOpenUrl(r.downloadUrl) }.padding(vertical = 8.dp),
            style = VibeLinkType.Caption, color = VibeLinkTheme.colors.ink3, textAlign = TextAlign.Center,
        )
        r.mirrorUrl?.let { url ->
            VibeLinkButton(AboutCopy.UPDATE_MIRROR, { onOpenUrl(url) }, Modifier.fillMaxWidth().padding(top = 10.dp), kind = ButtonKind.Tonal)
        }
        Text(
            AboutCopy.UPDATE_LATER,
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onDismiss).padding(vertical = 10.dp),
            style = VibeLinkType.Caption, color = VibeLinkTheme.colors.ink3, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChangelogSheet(latest: ReleaseInfo?, onOpenAll: () -> Unit, onDismiss: () -> Unit) {
    val c = VibeLinkTheme.colors
    AboutSheetFrame(AboutCopy.CHANGELOG, null, "changelog-sheet", onDismiss) {
        if (latest == null) {
            Text(AboutCopy.CHANGELOG_EMPTY, Modifier.padding(top = 10.dp), style = VibeLinkType.Caption, color = c.ink3)
        } else {
            Text(
                latest.version + (latest.date?.let { " · $it" } ?: ""), Modifier.padding(top = 10.dp),
                style = VibeLinkType.Label, color = c.ink,
            )
            if (latest.notes.isNotBlank()) NotesBox(latest.notes)
        }
        VibeLinkButton(AboutCopy.CHANGELOG_ALL, onOpenAll, Modifier.fillMaxWidth().padding(top = 14.dp), kind = ButtonKind.Tonal)
    }
}

@Composable
private fun FeedbackSheet(onGitHub: () -> Unit, onUpstream: () -> Unit, onDiagnostics: () -> Unit, onDismiss: () -> Unit) {
    AboutSheetFrame(AboutCopy.FEEDBACK, AboutCopy.FEEDBACK_SHEET_SUB, "feedback-sheet", onDismiss) {
        Column(Modifier.padding(top = 6.dp)) {
            FeedbackOption(VibeLinkIcons.Code, AboutCopy.FEEDBACK_GITHUB, AboutCopy.FEEDBACK_GITHUB_SUB, first = true, onGitHub)
            FeedbackOption(VibeLinkIcons.Code, AboutCopy.UPSTREAM, AboutCopy.UPSTREAM_SUB, first = false, onUpstream)
            FeedbackOption(VibeLinkIcons.Copy, AboutCopy.DIAGNOSTICS, AboutCopy.DIAGNOSTICS_SUB, first = false, onDiagnostics)
        }
    }
}

@Composable
private fun FeedbackOption(icon: ImageVector, title: String, sub: String, first: Boolean, onClick: () -> Unit) {
    Box(Modifier.testTag("feedback-option")) {
        ListRow(icon, title, sub, first = first, onClick = onClick)
    }
}

@Composable
private fun GroupSheet(onSave: () -> Unit, onAskVibeLink: () -> Unit, onDismiss: () -> Unit) {
    val c = VibeLinkTheme.colors
    // 点一次就够:连点不会开出第二个一样的任务
    var asked by remember { mutableStateOf(false) }
    // 二维码最高占屏幕约三分之一,常见手机上不用滚就能看到底下的按钮
    val qrHeight = (LocalConfiguration.current.screenHeightDp * 0.34f).coerceAtMost(280f).dp
    val qr = painterResource(QrImage.RES)
    AboutSheetFrame(AboutCopy.GROUP_SHEET_TITLE, AboutCopy.GROUP_SHEET_SUB, "group-sheet", onDismiss) {
        Image(
            qr, contentDescription = AboutCopy.GROUP_SHEET_TITLE,
            Modifier.padding(top = 12.dp, bottom = 6.dp).align(Alignment.CenterHorizontally)
                .height(qrHeight).aspectRatio(qr.intrinsicSize.width / qr.intrinsicSize.height)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(Unit) { detectTapGestures(onLongPress = { onSave() }) }
                .testTag("wechat-qr"),
        )
        Text(AboutCopy.GROUP_HINT, Modifier.fillMaxWidth(), style = VibeLinkType.Hint, color = c.ink3, textAlign = TextAlign.Center)
        VibeLinkButton(AboutCopy.SAVE_QR, onSave, Modifier.fillMaxWidth().padding(top = 14.dp), kind = ButtonKind.Tonal)
        HorizontalDivider(Modifier.padding(top = 16.dp), color = c.hair)
        Text(AboutCopy.GROUP_ALREADY_ADDED, Modifier.fillMaxWidth().padding(top = 12.dp), style = VibeLinkType.Hint, color = c.ink3, textAlign = TextAlign.Center)
        VibeLinkButton(
            AboutCopy.GROUP_ASK_VIBELINK, { if (!asked) { asked = true; onAskVibeLink() } },
            Modifier.fillMaxWidth().padding(top = 8.dp).testTag("group-ask-vibelink"), enabled = !asked,
        )
    }
}


