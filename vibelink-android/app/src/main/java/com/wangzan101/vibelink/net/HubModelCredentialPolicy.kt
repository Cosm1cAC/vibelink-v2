package com.wangzan101.vibelink.net

/** A queued SDK request must retain its original authenticated account. */
internal object HubModelCredentialPolicy {
    fun matches(authorizations: List<String>, meshTokens: List<String>, currentToken: String): Boolean {
        if (currentToken.isBlank() || authorizations.isEmpty() && meshTokens.isEmpty()) return false
        return authorizations.all { header ->
            val parts = header.trim().split(Regex("\\s+"), limit = 2)
            parts.size == 2 && parts[0].equals("Bearer", true) && parts[1] == currentToken
        } && meshTokens.all { it.trim() == currentToken }
    }
}
