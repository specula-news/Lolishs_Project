package app.lumen.player.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets (the Li'Server password) with a key that never leaves the Android Keystore. */
object SecureStore {
	private const val KEY_ALIAS = "lolish_secrets"
	private const val TRANSFORMATION = "AES/GCM/NoPadding"

	private fun key(): SecretKey {
		val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
		(store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
		generator.init(
			KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.build()
		)
		return generator.generateKey()
	}

	fun encrypt(plain: String): String? = runCatching {
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, key())
		val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
		val data = Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
		"$iv:$data"
	}.onFailure { Log.w("SecureStore", "Encrypt failed", it) }.getOrNull()

	fun decrypt(stored: String?): String? {
		if (stored.isNullOrBlank()) return null
		return runCatching {
			val (iv, data) = stored.split(':', limit = 2)
			val cipher = Cipher.getInstance(TRANSFORMATION)
			cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
			String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
		}.onFailure { Log.w("SecureStore", "Decrypt failed", it) }.getOrNull()
	}
}
