package com.wangzan101.mika.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.about.CheckStatus
import com.wangzan101.mika.about.UpdateState
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.IconTile
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 上手页也能看到更新：以前只有「设置 › 关于」里有，第一次装完的人卡在引导页，
 * 永远走不到那里。顺带把检查状态显出来，手机没连上 hub 时一眼能看出来。
 */
@Composable
fun UpdateBanner(version: String, update: UpdateState, onUpdate: () -> Unit, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val newer = update.update
    when {
        newer != null -> GroupCard(modifier) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onUpdate).padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(MikaIcons.Refresh)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text("发现新版本 ${newer.version}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                    Text("当前 $version · 点这里下载安装", Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink2)
                }
            }
        }

        update.status == CheckStatus.Failed -> Text(
            "更新检查失败：手机连不上 vibelink hub（当前 $version）",
            modifier = modifier.padding(top = 10.dp),
            style = MikaType.Hint, fontSize = 12.sp, color = c.ink3,
        )

        else -> Text(
            if (update.latest != null) "已是最新 $version" else "当前 $version",
            modifier = modifier.padding(top = 10.dp),
            style = MikaType.Hint, fontSize = 12.sp, color = c.ink3,
        )
    }
}
