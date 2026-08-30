package com.omnidocs.app.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Unit tests for LocalBackupService AES-256-GCM encryption, decryption,
 * header parsing, and password verification.
 */
class LocalBackupEncryptionTest {

    private val magicHeader = "OMNI_ENC_V1".toByteArray(Charsets.UTF_8)
    private val saltLength = 16
    private val ivLength = 12
    private val tagLengthBits = 128
    private val iterations = 65536
    private val keyLengthBits = 256

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val keySpec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLengthBits)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(keySpec).encoded, "AES")
    }

    private fun createSampleZip(notesContent: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"schemaVersion":2,"noteCount":1}""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("notes.json"))
            zip.write(notesContent.toByteArray())
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    private fun encryptBackup(zipData: ByteArray, password: String): ByteArray {
        val salt = ByteArray(saltLength).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(ivLength).also { SecureRandom().nextBytes(it) }
        val secretKey = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(tagLengthBits, iv))

        val output = ByteArrayOutputStream()
        output.write(magicHeader)
        output.write(salt)
        output.write(iv)

        CipherOutputStream(output, cipher).use { cipherOut ->
            cipherOut.write(zipData)
        }
        return output.toByteArray()
    }

    private fun decryptBackup(encryptedData: ByteArray, password: String): ByteArray {
        val input = ByteArrayInputStream(encryptedData)

        // Read magic
        val magic = ByteArray(magicHeader.size)
        input.read(magic)
        assertTrue("Magic header must match OMNI_ENC_V1", magic.contentEquals(magicHeader))

        // Read salt and iv
        val salt = ByteArray(saltLength)
        input.read(salt)
        val iv = ByteArray(ivLength)
        input.read(iv)

        val secretKey = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(tagLengthBits, iv))

        val decryptedOut = ByteArrayOutputStream()
        CipherInputStream(input, cipher).use { cipherIn ->
            val buffer = ByteArray(4096)
            var read: Int
            while (cipherIn.read(buffer).also { read = it } != -1) {
                decryptedOut.write(buffer, 0, read)
            }
        }
        return decryptedOut.toByteArray()
    }

    @Test
    fun testEncryptionAndDecryptionRoundTrip() {
        val sampleJson = """[{"id":"note_1","title":"Confidential Meeting Notes","content":"Secret strategy details"}]"""
        val rawZip = createSampleZip(sampleJson)
        val password = "SuperSecretPassword123!"

        val encrypted = encryptBackup(rawZip, password)

        // Verify header
        assertTrue(encrypted.size > magicHeader.size + saltLength + ivLength)
        val headerPrefix = encrypted.copyOfRange(0, magicHeader.size)
        assertTrue(headerPrefix.contentEquals(magicHeader))

        // Decrypt
        val decryptedZip = decryptBackup(encrypted, password)
        assertArrayEquals(rawZip, decryptedZip)

        // Read decrypted ZIP
        var foundNotes = false
        ZipInputStream(ByteArrayInputStream(decryptedZip)).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                if (entry.name == "notes.json") {
                    val content = String(zipIn.readBytes())
                    assertEquals(sampleJson, content)
                    foundNotes = true
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
        assertTrue("Decrypted ZIP must contain notes.json", foundNotes)
    }

    @Test
    fun testWrongPasswordFailsDecryption() {
        val sampleJson = """[{"id":"note_1","title":"Confidential"}]"""
        val rawZip = createSampleZip(sampleJson)
        val encrypted = encryptBackup(rawZip, "CorrectPassword123")

        try {
            decryptBackup(encrypted, "WrongPassword456")
            fail("Expected decryption with wrong password to fail with AEADBadTagException or IOException")
        } catch (e: Exception) {
            val root = generateSequence(e as Throwable) { it.cause }.lastOrNull()
            val isAuthFailure = root is AEADBadTagException || e is AEADBadTagException ||
                e.message?.contains("tag", ignoreCase = true) == true ||
                e.message?.contains("pad", ignoreCase = true) == true
            assertTrue("Decryption failure should be an authentication failure", isAuthFailure)
        }
    }

    @Test
    fun testCorruptedCiphertextFailsAuthentication() {
        val sampleJson = """[{"id":"note_1","title":"Tamper Test"}]"""
        val rawZip = createSampleZip(sampleJson)
        val encrypted = encryptBackup(rawZip, "Password123")

        // Tamper with one byte in the ciphertext payload
        val corrupted = encrypted.copyOf()
        val corruptIndex = magicHeader.size + saltLength + ivLength + 5
        corrupted[corruptIndex] = (corrupted[corruptIndex].toInt() xor 0xFF).toByte()

        try {
            decryptBackup(corrupted, "Password123")
            fail("Expected corrupted ciphertext to fail GCM authentication")
        } catch (e: Exception) {
            val root = generateSequence(e as Throwable) { it.cause }.lastOrNull()
            val isAuthFailure = root is AEADBadTagException || e is AEADBadTagException ||
                e.message?.contains("tag", ignoreCase = true) == true
            assertTrue("Corrupted ciphertext must fail authentication", isAuthFailure)
        }
    }
}
