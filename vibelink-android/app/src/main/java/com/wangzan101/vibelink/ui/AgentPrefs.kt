package com.wangzan101.vibelink.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * agent 列表的本地状态：置顶、已读时间、免打扰、自定义头像。
 * 只存在手机上，不上报 hub——这些是「我看它」的偏好，不是 agent 的状态。
 */
object AgentPrefs {
    private const val FILE = "vibelink_agent_prefs"
    private const val PIN_KEY = "pins"

    private val _avatarVersionFlow = MutableStateFlow(0)
    val avatarVersionFlow: StateFlow<Int> = _avatarVersionFlow

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun pins(c: Context): Set<String> =
        prefs(c).getString(PIN_KEY, "").orEmpty()
            .split(',')
            .filter { it.isNotBlank() }
            .toSet()

    fun setPinned(c: Context, id: String, pinned: Boolean) {
        val next = pins(c).toMutableSet()
        if (pinned) next.add(id) else next.remove(id)
        prefs(c).edit().putString(PIN_KEY, next.joinToString(",")).apply()
    }

    fun lastSeen(c: Context, id: String): Long = prefs(c).getLong("seen:$id", 0L)

    fun markSeen(c: Context, id: String, at: Long = System.currentTimeMillis()) {
        prefs(c).edit().putLong("seen:$id", at).apply()
    }

    /** 第一次见这个 agent：把已读时间设成现在，避免把全部历史会话算成未读。 */
    fun ensureSeen(c: Context, id: String): Long {
        val seen = lastSeen(c, id)
        if (seen == 0L) {
            val now = System.currentTimeMillis()
            markSeen(c, id, now)
            return now
        }
        return seen
    }

    fun muted(c: Context, id: String): Boolean = prefs(c).getBoolean("muted:$id", false)

    fun setMuted(c: Context, id: String, muted: Boolean) {
        prefs(c).edit().putBoolean("muted:$id", muted).apply()
    }

    /** 读取指定 Agent / Bot 的自定义或预设头像标识（空串表示使用该底层模型的默认 AI 拟人头像） */
    fun avatar(c: Context, id: String): String =
        prefs(c).getString("avatar:$id", "").orEmpty()

    /** 设置指定 Agent / Bot 的自定义头像（支持 "preset:gpt" / "preset:claude" / "preset:glm" / "preset:deepseek" 或 "data:image/jpeg;base64,..."） */
    fun setAvatar(c: Context, id: String, spec: String) {
        val clean = spec.trim()
        val editor = prefs(c).edit()
        if (clean.isEmpty()) {
            editor.remove("avatar:$id")
        } else {
            editor.putString("avatar:$id", clean)
        }
        editor.apply()
        _avatarVersionFlow.value++
    }
}
