package com.wangzan101.vibelink.remote

import org.json.JSONArray
import org.json.JSONObject

internal object SessionJson {
    fun parse(o: JSONObject): ClientSession {
        val p = o.optJSONArray("permissions") ?: JSONArray()
        return ClientSession(o.optString("userId"), o.optString("label"),
            if (o.isNull("tenantId")) null else o.optString("tenantId").takeIf { it.isNotBlank() },
            o.optString("edition"), o.optString("sessionId"), o.optLong("expiresAt"),
            (0 until p.length()).map { p.optString(it) }, o.optString("issuer"), o.optString("hubId"))
    }
    fun encode(s: ClientSession): JSONObject = JSONObject().put("userId", s.userId).put("label", s.label)
        .put("tenantId", s.tenantId ?: JSONObject.NULL).put("edition", s.edition).put("sessionId", s.sessionId)
        .put("expiresAt", s.expiresAt).put("permissions", JSONArray(s.permissions)).put("issuer", s.issuer).put("hubId", s.hubId)
}
