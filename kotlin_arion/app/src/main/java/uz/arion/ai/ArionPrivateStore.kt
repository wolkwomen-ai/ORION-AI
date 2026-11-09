package uz.arion.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted local vault for private ARION profile data.
 * AES keys stay inside Android Keystore; ciphertext and IV are stored in app-private preferences.
 */
class ArionPrivateStore(context: Context) {
    private val prefs = context.getSharedPreferences("arion_private_vault", Context.MODE_PRIVATE)
    private val keyAlias = "arion_private_vault_aes_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun put(name: String, value: String) {
        require(name.matches(Regex("[A-Za-z0-9_]{1,64}"))) { "Noto‘g‘ri maxfiy yozuv nomi" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("${name}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("${name}_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun get(name: String): String? {
        require(name.matches(Regex("[A-Za-z0-9_]{1,64}"))) { "Noto‘g‘ri maxfiy yozuv nomi" }
        return try {
            val iv = prefs.getString("${name}_iv", null) ?: return null
            val data = prefs.getString("${name}_data", null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun remove(name: String) {
        prefs.edit().remove("${name}_iv").remove("${name}_data").apply()
    }
}
