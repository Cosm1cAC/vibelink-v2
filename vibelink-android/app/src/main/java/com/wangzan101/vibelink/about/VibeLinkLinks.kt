package com.wangzan101.vibelink.about

import com.wangzan101.vibelink.R
import com.wangzan101.vibelink.i18n.tr
import java.net.URLEncoder

/** VibeLink 对外的地址都在这里:仓库、问题、作者、检查更新用的两个来源。换地址只改这一处。 */
object VibeLinkLinks {
    const val REPO = "https://github.com/Cosm1cAC/vibelink-v2"
    const val REPO_LABEL = "github.com/Cosm1cAC/vibelink-v2"
    const val README = "$REPO#readme"
    const val RELEASES = "$REPO/releases"
    const val NEW_ISSUE = "$REPO/issues/new"
    /** 上游 VibeLink 的作者主页（只用于致谢，不再提供「替我联系作者」之类的自动操作）。 */
    const val UPSTREAM = "https://github.com/wangzan101/Mika"

    /** 公网入口（Cloudflare 隧道）。安装包清单是公开接口，地址里不带任何 token。 */
    const val PUBLIC_HUB = "https://hub.vibelink.cloud"

    const val APK_LATEST_PATH = "/api/apk/latest"

    /** 热点外走 Cloudflare 隧道拿清单。 */
    const val LATEST_RELEASE_API_PUBLIC = PUBLIC_HUB + APK_LATEST_PATH

    /** 当前配对的 hub 上的安装包清单。 */
    fun latestReleaseApi(hub: String): String = hub.trimEnd('/') + APK_LATEST_PATH

    /** 本项目自己的 GitHub Release（发布页上传 APK 后即可作为独立更新源）。 */
    const val LATEST_RELEASE_API_GITHUB = "https://api.github.com/repos/Cosm1cAC/vibelink-v2/releases/latest"

    /** 反馈时预先填进问题模板的设备与版本信息。 */
    data class Device(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val versionName: String,
        val build: String,
    )

    /** 预先填好的问题正文:机型、安卓版本、VibeLink 版本三行,下面留空几段给用户写。 */
    fun issueBody(d: Device): String {
        val model = if (d.model.startsWith(d.manufacturer, ignoreCase = true)) d.model else "${d.manufacturer} ${d.model}"
        return buildString {
            append(tr(R.string.issue_body, model.trim(), d.androidRelease, d.sdk, d.versionName, d.build))
        }
    }

    /** 新建问题的地址,标题留空、正文预先填好;参数按 URL 编码(空格写成 %20)。 */
    fun issueUrl(d: Device): String = "$NEW_ISSUE?title=&body=${encode(issueBody(d))}"

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}


