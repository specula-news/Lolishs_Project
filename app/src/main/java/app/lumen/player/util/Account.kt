package app.lumen.player.util

import org.json.JSONArray
import org.json.JSONObject

/** A Jellyfin user saved on this TV ("profile"). The token stays valid until it is signed out. */
data class Account(
	val serverUrl: String,
	val serverName: String?,
	val userId: String,
	val userName: String,
	val token: String,
	val deviceId: String,
	val imageTag: String?,
	/** Password encrypted with the Android Keystore, so an expired sign-in can be renewed by itself. */
	val encryptedPassword: String? = null,
) {
	val key: String get() = "$serverUrl|$userId"

	fun sameUser(other: Account) = serverUrl.trimEnd('/') == other.serverUrl.trimEnd('/') && userId == other.userId

	fun imageUrl(maxWidth: Int = 240): String? =
		imageTag?.let { "${serverUrl.trimEnd('/')}/Users/$userId/Images/Primary?maxWidth=$maxWidth&tag=$it" }

	private fun toJson(): JSONObject = JSONObject()
		.put("server", serverUrl)
		.put("serverName", serverName ?: JSONObject.NULL)
		.put("userId", userId)
		.put("userName", userName)
		.put("token", token)
		.put("deviceId", deviceId)
		.put("imageTag", imageTag ?: JSONObject.NULL)
		.put("pw", encryptedPassword ?: JSONObject.NULL)

	companion object {
		fun parseList(raw: String): List<Account> = runCatching {
			val array = JSONArray(raw)
			(0 until array.length()).mapNotNull { index ->
				val o = array.optJSONObject(index) ?: return@mapNotNull null
				val server = o.optString("server").takeIf { it.isNotBlank() } ?: return@mapNotNull null
				val userId = o.optString("userId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
				val token = o.optString("token").takeIf { it.isNotBlank() } ?: return@mapNotNull null
				Account(
					serverUrl = server,
					serverName = o.optString("serverName").takeIf { it.isNotBlank() && it != "null" },
					userId = userId,
					userName = o.optString("userName").ifBlank { "User" },
					token = token,
					deviceId = o.optString("deviceId").ifBlank { userId },
					imageTag = o.optString("imageTag").takeIf { it.isNotBlank() && it != "null" },
					encryptedPassword = o.optString("pw").takeIf { it.isNotBlank() && it != "null" },
				)
			}
		}.getOrDefault(emptyList())

		fun toJson(list: List<Account>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
	}
}
