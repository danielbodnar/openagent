package sh.sandboxed.dashboard.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/// Encrypts the session JWT at rest with an AES-GCM key held in the Android
/// Keystore, so a copied DataStore file alone can't replay the session.
///
/// Stored format: `enc:<base64 iv>:<base64 ciphertext>`. Values without the
/// prefix are treated as legacy plaintext so existing installs keep working;
/// they get encrypted on the next token write. Any failure decrypting returns
/// null, which surfaces as a signed-out state rather than a crash.
object TokenCrypto {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "sandboxed.jwt"
    private const val PREFIX = "enc:"

    fun encrypt(plain: String): String = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX +
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }.getOrDefault(plain) // Keystore unavailable: fall back to plaintext rather than lose the session.

    fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val parts = stored.removePrefix(PREFIX).split(":", limit = 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
