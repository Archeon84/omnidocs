package com.omnidocs.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val MASTER_KEY_ALIAS = "omnidocs_master_key"
private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
private const val GCM_IV_LENGTH = 12
private const val GCM_TAG_LENGTH = 128

/**
 * Manages hardware-backed AES-256-GCM encryption keys via the Android KeyStore.
 * Used for encrypting database passphrases, tokens, and sensitive workspace material.
 */
@Singleton
class KeyStoreManager @Inject constructor() {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    /**
     * Retrieves or generates the hardware-backed AES master key.
     */
    fun getOrCreateMasterKey(): SecretKey {
        if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }
        return (keyStore.getEntry(MASTER_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    /**
     * Encrypts plaintext bytes using AES-256-GCM and prepends the random 12-byte IV.
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val secretKey = getOrCreateMasterKey()
        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    /**
     * Decrypts ciphertext bytes with prepended 12-byte IV.
     */
    fun decrypt(ivAndCiphertext: ByteArray): ByteArray {
        if (ivAndCiphertext.size < GCM_IV_LENGTH) {
            throw IllegalArgumentException("Invalid ciphertext length: must be at least $GCM_IV_LENGTH bytes")
        }
        val secretKey = getOrCreateMasterKey()
        val iv = ivAndCiphertext.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = ivAndCiphertext.copyOfRange(GCM_IV_LENGTH, ivAndCiphertext.size)

        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Encrypts a string and returns a Base64 string.
     */
    fun encryptString(plaintext: String): String {
        val encrypted = encrypt(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /**
     * Decrypts a Base64 string back to plaintext.
     */
    fun decryptString(base64Ciphertext: String): String {
        val bytes = Base64.decode(base64Ciphertext, Base64.NO_WRAP)
        val decrypted = decrypt(bytes)
        return String(decrypted, Charsets.UTF_8)
    }
}
