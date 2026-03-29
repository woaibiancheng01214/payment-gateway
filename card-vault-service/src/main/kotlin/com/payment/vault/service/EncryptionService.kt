package com.payment.vault.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Service
class EncryptionService(
    @Value("\${vault.encryption.key:}") private val encryptionKey: String,
    @Value("\${vault.encryption.key-file:}") private val encryptionKeyFile: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
    }

    private val secretKey: SecretKeySpec by lazy {
        val keyHex = resolveKey()
        val keyBytes = hexToBytes(keyHex)
        SecretKeySpec(keyBytes, "AES")
    }

    private fun resolveKey(): String {
        // Prefer file-based key (Docker secrets) over inline property
        if (encryptionKeyFile.isNotBlank()) {
            val path = Paths.get(encryptionKeyFile)
            if (Files.exists(path)) {
                log.info("Loading encryption key from file: $encryptionKeyFile")
                return Files.readString(path).trim()
            }
        }
        if (encryptionKey.isNotBlank()) {
            return encryptionKey
        }
        throw IllegalStateException("No encryption key configured — set vault.encryption.key or vault.encryption.key-file")
    }

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

        return Base64.getEncoder().encodeToString(combined)
    }

    fun decrypt(ciphertext: String): String {
        val combined = Base64.getDecoder().decode(ciphertext)

        val iv = combined.copyOfRange(0, IV_LENGTH)
        val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))

        val plaintext = cipher.doFinal(encrypted)
        return String(plaintext, Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have an even length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
