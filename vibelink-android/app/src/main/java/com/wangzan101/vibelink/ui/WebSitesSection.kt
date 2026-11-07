package com.wangzan101.vibelink.ui

import com.wangzan101.vibelink.R
import com.wangzan101.vibelink.i18n.tr
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.wangzan101.vibelink.cap.web.SiteEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.wangzan101.vibelink.i18n.uiLocale
import com.wangzan101.vibelink.ui.components.GroupCard
import com.wangzan101.vibelink.ui.components.GroupLabel
import com.wangzan101.vibelink.ui.components.ListRow
import com.wangzan101.vibelink.ui.components.RowValue
import com.wangzan101.vibelink.ui.components.ValueTone
import com.wangzan101.vibelink.ui.theme.VibeLinkTheme
import com.wangzan101.vibelink.ui.theme.VibeLinkIcons
import com.wangzan101.vibelink.ui.components.TileTone
import com.wangzan101.vibelink.ui.components.FooterNote

/**
 * 设置页「网站登录」一节的数据与操作。[onOpenLogin] 为 null 时不提供「打开登录」。
 * [onLogout] 返回过期掉的 Cookie 个数。
 */
class WebSitesState(
    val sites: List<SiteEntry>,
    val onOpenLogin: ((String) -> Unit)?,
    val onLogout: (String) -> Int,
    val onLogoutAll: () -> Unit,
)

val WEB_LOGOUT_PARTIAL: String get() = tr(R.string.web_logout_partial)
val WEB_CHALLENGED_NOTE: String get() = tr(R.string.web_challenged_note)
val WEB_DATA_NOTE: String get() = tr(R.string.web_data_note)

/** 设置页的「网站登录」:列出内置浏览器访问过的网站(登录过的在前),可以打开登录、退出单个网站、全部退出。 */
@Composable
fun WebSitesSection(state: WebSitesState, labeled: Boolean = true) {
    var picked by remember { mutableStateOf<SiteEntry?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    if (labeled) GroupLabel(tr(R.string.settings_web_logins))
    GroupCard {
        if (state.sites.isEmpty()) {
            ListRow(VibeLinkIcons.Globe, tr(R.string.web_none), tr(R.string.web_none_sub), first = true)
        }
        val now = System.currentTimeMillis()
        state.sites.forEachIndexed { i, s ->
            ListRow(
                VibeLinkIcons.Globe, s.host,
                subtitle = listOfNotNull(
                    tr(R.string.web_signed_in_by_you).takeIf { s.loggedInByUser },
                    tr(R.string.web_challenged).takeIf { s.challenged(now) },
                    tr(R.string.web_last_visit, day(s.lastVisitMs)),
                ).joinToString(" · "),
                first = i == 0,
                onClick = { picked = s },
            ) { RowValue(tr(R.string.web_manage), ValueTone.Link) }
        }
        ListRow(
            VibeLinkIcons.Close, tr(R.string.web_logout_all_title), titleColor = VibeLinkTheme.colors.bad, tone = TileTone.Bad,
            onClick = { confirmAll = true },
        )
    }
    FooterNote(WEB_DATA_NOTE)

    picked?.let { s ->
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(s.host) },
            text = {
                val base = if (s.loggedInByUser) tr(R.string.web_you_signed_in) else tr(R.string.web_vibelink_visited)
                Text(if (s.challenged(System.currentTimeMillis())) "$base\n\n$WEB_CHALLENGED_NOTE" else base)
            },
            confirmButton = {
                Column {
                    state.onOpenLogin?.let { open ->
                        TextButton({ picked = null; open(s.host) }) { Text(tr(R.string.web_open_login)) }
                    }
                    val clearing = s.challenged(System.currentTimeMillis())
                    TextButton({
                        picked = null
                        state.onLogout(s.host)
                        notice = if (clearing) tr(R.string.web_cleared, s.host) else tr(R.string.web_signed_out, s.host, WEB_LOGOUT_PARTIAL)
                    }) { Text(if (clearing) tr(R.string.web_clear_site) else tr(R.string.web_sign_out_site), color = VibeLinkTheme.colors.bad) }
                }
            },
            dismissButton = { TextButton({ picked = null }) { Text(tr(R.string.cancel)) } },
        )
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(tr(R.string.web_logout_all_q)) },
            text = { Text(tr(R.string.web_logout_all_body)) },
            confirmButton = {
                TextButton({ confirmAll = false; state.onLogoutAll(); notice = tr(R.string.web_logged_out_all) }) {
                    Text(tr(R.string.web_logout_all), color = VibeLinkTheme.colors.bad)
                }
            },
            dismissButton = { TextButton({ confirmAll = false }) { Text(tr(R.string.cancel)) } },
        )
    }

    notice?.let { n ->
        AlertDialog(
            onDismissRequest = { notice = null },
            text = { Text(n) },
            confirmButton = { TextButton({ notice = null }) { Text(tr(R.string.ok_got_it)) } },
        )
    }
}

private fun day(ms: Long): String = SimpleDateFormat(tr(R.string.date_pattern_md_compact), uiLocale()).format(Date(ms))
