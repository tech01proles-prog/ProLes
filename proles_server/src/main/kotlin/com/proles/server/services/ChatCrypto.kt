package com.proles.server.services

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ChatCrypto {
    private val random = SecureRandom()
    private val keyBytes: ByteArray by lazy {
        val configured = System.getenv("CHAT_ENCRYPTION_KEY")?.trim().orEmpty()
        require(configured.isNotBlank()) { "CHAT_ENCRYPTION_KEY is required" }
        runCatching { Base64.getDecoder().decode(configured) }.getOrElse { configured.toByteArray(Charsets.UTF_8) }.let { if (it.size in setOf(16, 24, 32)) it else MessageDigest.getInstance("SHA-256").digest(it) }
    }

    fun encrypt(value: String): Pair<String, String> {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8))) to Base64.getEncoder().encodeToString(iv)
    }

    fun decrypt(ciphertext: String, iv: String): String {
        if (ciphertext.isBlank()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, Base64.getDecoder().decode(iv)))
        return String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), Charsets.UTF_8)
    }

    fun encryptStream(input: InputStream, output: OutputStream): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val buffer = ByteArray(64 * 1024)
        var read: Int
        while (input.read(buffer).also { read = it } >= 0) {
            if (read > 0) cipher.update(buffer, 0, read)?.let(output::write)
        }
        cipher.doFinal()?.let(output::write)
        return Base64.getEncoder().encodeToString(iv)
    }

    fun decryptStream(input: InputStream, output: OutputStream, encodedIv: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, Base64.getDecoder().decode(encodedIv)))
        val buffer = ByteArray(64 * 1024)
        var read: Int
        while (input.read(buffer).also { read = it } >= 0) {
            if (read > 0) cipher.update(buffer, 0, read)?.let(output::write)
        }
        cipher.doFinal()?.let(output::write)
    }

}