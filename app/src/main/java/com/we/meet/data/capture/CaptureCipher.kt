package com.we.meet.data.capture

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore key material is never exported; each row is bound to its account and identity. */
internal class CaptureCipher(private val key: SecretKey, private val scope: String) {
    fun encrypt(label: String, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD("$scope/$label".toByteArray(Charsets.UTF_8))
        require(cipher.iv.size == 12)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(label: String, bytes: ByteArray): ByteArray {
        require(bytes.size >= 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD("$scope/$label".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(13, bytes.size))
    }

    companion object {
        @Synchronized
        fun open(scope: String, existingData: Boolean): CaptureCipher {
            val alias = "meeting-capture-v1-$scope"
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(alias, null) as? SecretKey
            // Lost keys must surface a recovery error, not silently replace unreadable recordings.
            check(existing != null || !existingData) { "Capture encryption key unavailable" }
            val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
                generateKey()
            }
            return CaptureCipher(key, scope)
        }
    }
}
