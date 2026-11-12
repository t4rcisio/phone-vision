package com.example.vision

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
 * Encrypts the user-supplied API key with AES-GCM using a non-exportable Android Keystore key. Only
 * the ciphertext and per-write IV enter private preferences. Plaintext credentials must never
 * appear in logs, build configuration, screenshots, source control, or application backups.
 */
class KeyVault(
    context: Context,
    private val name: String = "moondream",
    prefsName: String = "credentials",
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(name, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            name,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }
            .generateKey()
    }

    fun read(): String = runCatching {
        val value = prefs.getString("value", null) ?: return ""
        val iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), Charsets.UTF_8)
    }
        .getOrDefault("")

    fun save(value: String) {
        if (value.isBlank()) {
            check(prefs.edit().clear().commit()) { "Could not remove the key" }
            return
        }
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(
            prefs
                .edit()
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(
                    "value",
                    Base64.encodeToString(
                        cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
                        Base64.NO_WRAP,
                    ),
                )
                .commit(),
        ) {
            "Could not persist the key"
        }
        check(read() == value) { "Could not verify the saved key" }
    }
}
