package com.wangzan101.vibelink.settings

import android.content.Context
import com.wangzan101.vibelink.VibeLinkApp
import com.wangzan101.vibelink.remote.RemoteStore
import com.wangzan101.vibelink.core.model.Capabilities
import com.wangzan101.vibelink.core.model.ModelRole
import com.wangzan101.vibelink.ui.OnboardingState

/**
 * 首次启动配置独立 DeepSeek API 通道。订阅模型只通过电脑端官方 CLI。
 *
 * 不走 VibeLink 原来的「选服务商 → 填 Base URL/Key → 试模型 → 选主模型」引导，
 * 装完直接能聊。用户想换模型，去「设置 › 模型」改就行（我们只写一次）。
 */
object Preconfigure {
    private const val PREFS = "vibelink_preconfigure"
    private const val DONE = "done"
    private const val CONFIG_ID = "vibelink-hub"

    /** Hub 的 DeepSeek API 入口。客户端凭配对或租户 token 鉴权。 */
    fun baseUrl(hub: String): String = hub.trimEnd('/') + "/api/llm/v1"
    const val MODEL = "deepseek-flash"

    /**
     * 配对成功后调：把 vibelink 服务商的地址和 Key 换成当前 hub 与新 token
     * （老版本 APK 存的是内置的共享 token，轮换后已失效）。还没配过就顺手配上。
     */
    fun onPaired(context: Context) {
        val app = context.applicationContext as? VibeLinkApp ?: return
        val token = RemoteStore.token(context)
        if (token.isBlank()) return
        val existing = app.providers.configs.value.firstOrNull { it.id == CONFIG_ID }
        if (existing != null) {
            // providerId 置空，让 ProviderStore 按新 baseUrl 绑定对应 Provider，避免被旧 Provider 的 baseUrl 覆盖回退
            app.providers.save(
                existing.copy(
                    baseUrl = baseUrl(RemoteStore.hub(context)),
                    providerId = "",
                    model = MODEL,
                ),
                token,
            )
            app.providers.choose(ModelRole.Main, RoleChoice.Model(CONFIG_ID))
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(DONE, false).apply()
            ensure(context)
        }
    }

    fun ensure(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val app = context.applicationContext as? VibeLinkApp ?: return
        // 没配对就没有可用的 Key，等配对后 [onPaired] 再配
        val token = RemoteStore.token(context)
        if (token.isBlank()) return
        val store = app.providers
        val existing = store.configs.value.firstOrNull { it.id == CONFIG_ID }
        if (existing != null) {
            val targetUrl = baseUrl(RemoteStore.hub(context))
            if (existing.baseUrl != targetUrl || existing.model != MODEL || store.apiKey(CONFIG_ID) != token) {
                store.save(existing.copy(baseUrl = targetUrl, providerId = "", model = MODEL), token)
            }
            if (store.roles.value.main == null || store.configFor(ModelRole.Main)?.kind == ProviderConfig.Kind.ChatGpt) {
                store.choose(ModelRole.Main, RoleChoice.Model(CONFIG_ID))
            }
            prefs.edit().putBoolean(DONE, true).apply()
            return
        }
        if (prefs.getBoolean(DONE, false) && store.roles.value.main != null) return
        // 用户已经自己配过主模型就别抢
        if (store.roles.value.main != null) {
            prefs.edit().putBoolean(DONE, true).apply()
            return
        }
        val cfg = ProviderConfig(
            id = CONFIG_ID,
            kind = ProviderConfig.Kind.OpenAiCompat,
            baseUrl = baseUrl(RemoteStore.hub(context)),
            model = MODEL,
            capabilities = Capabilities(
                toolCalls = true,
                vision = true,
                contextTokens = 1_000_000,
                reasoning = true,
            ),
            name = "VibeLink",
        )
        store.save(cfg, token)
        store.choose(ModelRole.Main, RoleChoice.Model(CONFIG_ID))
        OnboardingState(context).markModelSetupDone()
        prefs.edit().putBoolean(DONE, true).apply()
    }
}

