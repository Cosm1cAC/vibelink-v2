package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.ui.OnboardingState

/**
 * 首次启动就把模型配好：直接用 PC 上的 vibelink hub 转出来的 magpie。
 *
 * 不走 Mika 原来的「选服务商 → 填 Base URL/Key → 试模型 → 选主模型」引导，
 * 装完直接能聊。用户想换模型，去「设置 › 模型」改就行（我们只写一次）。
 */
object Preconfigure {
    private const val PREFS = "vibelink_preconfigure"
    private const val DONE = "done"
    private const val CONFIG_ID = "vibelink-hub"

    /** hub 的模型转发入口：/api/llm/v1 → 127.0.0.1:3425/v1（magpie）。 */
    const val BASE_URL = "http://192.168.137.1:18995/api/llm/v1"
    const val API_KEY = "000001a18bc5616395b46d2347c7ba7c"
    const val MODEL = "auto/deepseek-v4.1-flash"

    fun ensure(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(DONE, false)) return
        val app = context.applicationContext as? MikaApp ?: return
        val store = app.providers
        // 用户已经自己配过主模型就别抢
        if (store.roles.value.main != null) {
            prefs.edit().putBoolean(DONE, true).apply()
            return
        }
        val cfg = ProviderConfig(
            id = CONFIG_ID,
            kind = ProviderConfig.Kind.OpenAiCompat,
            baseUrl = BASE_URL,
            model = MODEL,
            capabilities = Capabilities(
                toolCalls = true,
                vision = true,
                contextTokens = 1_000_000,
                reasoning = true,
            ),
            name = "vibelink",
        )
        store.save(cfg, API_KEY)
        store.choose(ModelRole.Main, RoleChoice.Model(CONFIG_ID))
        OnboardingState(context).markModelSetupDone()
        prefs.edit().putBoolean(DONE, true).apply()
    }
}

