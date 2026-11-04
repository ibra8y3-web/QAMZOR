package org.qamzor.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small Android-Keystore-backed encrypted blob store for archive passwords and cloud secrets. */
internal object AndroidSecretVault {
    private const val KEY_ALIAS = "qamzor.secret.v1"
    private const val IV_BYTES = 12

    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun directory(context: Context): File = File(context.noBackupFilesDir, "qamzor-secure").apply {
        require(mkdirs() || isDirectory) { "Cannot initialize the encrypted QAMZOR vault" }
    }

    private fun target(context: Context, reference: String): File {
        require(reference.matches(Regex("[A-Za-z0-9_-]{8,80}"))) { "Invalid secret reference" }
        return File(directory(context), "$reference.bin")
    }

    fun put(context: Context, reference: String, cleartext: ByteArray) {
        require(cleartext.size <= 128 * 1024) { "Secret payload is too large" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = try { cipher.doFinal(cleartext) } finally { cleartext.fill(0) }
        val data = ByteBuffer.allocate(4 + cipher.iv.size + encrypted.size)
            .putInt(cipher.iv.size).put(cipher.iv).put(encrypted).array()
        val output = target(context, reference)
        val temporary = File.createTempFile(".secret-", ".tmp", output.parentFile)
        try {
            temporary.writeBytes(data)
            try {
                Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }

    fun get(context: Context, reference: String): ByteArray {
        val file = target(context, reference)
        require(file.isFile && file.length() in 17..(128L * 1024 + 64)) { "Encrypted secret is missing or invalid" }
        val buffer = ByteBuffer.wrap(file.readBytes())
        val ivLength = buffer.int
        require(ivLength == IV_BYTES && buffer.remaining() > ivLength) { "Encrypted secret envelope is malformed" }
        val iv = ByteArray(ivLength).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    fun remove(context: Context, reference: String) {
        target(context, reference).delete()
    }
}
