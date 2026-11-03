package com.wangzan101.mika.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.MainActivity
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 网络代理入口：VPN 已经交给 CMFA，这里只负责打开它的完整界面。 */
@Composable
fun ProxyScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = MikaTheme.colors
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar("网络代理", MikaIcons.Back, onBack, "返回")
            Column(Modifier.padding(20.dp)) {
                Text(
                    "VPN 由 CMFA 管理。状态、代理组、配置文件、日志都在 Clash 界面里。",
                    style = MikaType.Body,
                    color = c.ink2,
                )
                Spacer(Modifier.height(20.dp))
                MikaButton(
                    "打开 Clash",
                    { ctx.startActivity(Intent(ctx, MainActivity::class.java)) },
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
