package org.qamzor.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Interoperable Ed25519 detached signatures compatible with qamzor.signatures.py. */
object ArchiveSignatures {
    private const val SIGNATURE_DOMAIN = "QAMZOR-ED25519-ARCHIVE-SIGNATURE-V1\u0000"
    private val json = Json { ignoreUnknownKeys = false }

    data class SigningKeyPair(val privateKeyPkcs8: ByteArray, val publicKeyX509: ByteArray)
    data class SignatureResult(val signatureFile: File, val archiveSize: Long, val archiveSha256: String, val publicKeySha256: String)

    @Synchronized private fun provider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) Security.addProvider(BouncyCastleProvider())
    }

    fun generateKeyPair(): SigningKeyPair {
        provider()
        val pair = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME).generateKeyPair()
        return SigningKeyPair(pair.private.encoded, pair.public.encoded)
    }

    fun publicKeyPem(publicKeyX509: ByteArray): String {
        require(publicKeyX509.size in 32..4096) { "Invalid Ed25519 public key bytes" }
        val encoded = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(publicKeyX509)
        return "-----BEGIN PUBLIC KEY-----\n$encoded\n-----END PUBLIC KEY-----\n"
    }

    fun parsePublicKey(publicKeyFile: File): ByteArray {
        val text = publicKeyFile.readText(Charsets.US_ASCII).trim()
        val body = text.removePrefix("-----BEGIN PUBLIC KEY-----").removeSuffix("-----END PUBLIC KEY-----")
            .replace(Regex("\\s+"), "")
        return Base64.getDecoder().decode(body).also { require(it.size in 32..4096) { "Invalid public key file" } }
    }

    fun signArchive(archive: File, privateKeyPkcs8: ByteArray, publicKeyX509: ByteArray, output: File): SignatureResult {
        require(archive.isFile && archive.length() >= 32) { "Archive is missing or too small" }
        require(output.absoluteFile.parentFile == archive.absoluteFile.parentFile) { "Signature must be stored beside its archive" }
        require(isQzra(archive)) { "Only QZRA archives can be signed by this function" }
        provider()
        val privateKey = KeyFactory.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
            .generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8))
        val (digest, size) = digest(archive)
        val publicFingerprint = MessageDigest.getInstance("SHA-256").digest(publicKeyX509).hex()
        val signer = Signature.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        signer.initSign(privateKey)
        signer.update(signatureMessage(digest, size))
        val signature = signer.sign()
        val document = """
            {"algorithm":"Ed25519","archive_sha256":"${digest.hex()}","archive_size":$size,"format":"QZRA-SIGNATURE","public_key_sha256":"$publicFingerprint","signature":"${Base64.getEncoder().encodeToString(signature)}","version":1}
        """.trimIndent() + "\n"
        atomicWrite(output, document.toByteArray(Charsets.UTF_8))
        return SignatureResult(output, size, digest.hex(), publicFingerprint)
    }

    fun verifyArchive(archive: File, publicKeyX509: ByteArray, signatureFile: File): SignatureResult {
        require(archive.isFile && signatureFile.isFile) { "Archive or signature file is missing" }
        require(signatureFile.length() <= 64 * 1024) { "Signature file is too large" }
        require(isQzra(archive)) { "The selected file is not a QZRA archive" }
        val document = json.parseToJsonElement(signatureFile.readText(Charsets.UTF_8)).jsonObject
        require(document.getValue("format").jsonPrimitive.content == "QZRA-SIGNATURE" &&
            document.getValue("version").jsonPrimitive.content == "1" &&
            document.getValue("algorithm").jsonPrimitive.content == "Ed25519") { "Unsupported QAMZOR signature format" }
        val (digest, size) = digest(archive)
        val expectedDigest = document.getValue("archive_sha256").jsonPrimitive.content
        val expectedSize = document.getValue("archive_size").jsonPrimitive.content.toLong()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(publicKeyX509).hex()
        require(expectedDigest == digest.hex() && expectedSize == size) { "Archive bytes do not match the signed archive" }
        require(document.getValue("public_key_sha256").jsonPrimitive.content == fingerprint) { "Signature was created by another public key" }
        val signature = Base64.getDecoder().decode(document.getValue("signature").jsonPrimitive.content)
        provider()
        val key = KeyFactory.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
            .generatePublic(X509EncodedKeySpec(publicKeyX509))
        val verifier = Signature.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        verifier.initVerify(key)
        verifier.update(signatureMessage(digest, size))
        require(verifier.verify(signature)) { "Ed25519 signature verification failed" }
        return SignatureResult(signatureFile, size, digest.hex(), fingerprint)
    }

    private fun signatureMessage(digest: ByteArray, size: Long): ByteArray {
        require(size >= 0) { "Invalid archive size" }
        val sizeBytes = java.nio.ByteBuffer.allocate(8).putLong(size).array()
        return SIGNATURE_DOMAIN.toByteArray(Charsets.US_ASCII) + sizeBytes + digest
    }

    private fun digest(file: File): Pair<ByteArray, Long> {
        val hash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                size += count
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest() to size
    }

    private fun isQzra(file: File): Boolean = FileInputStream(file).use { input ->
        val signature = ByteArray(8)
        input.read(signature) == 8 && signature.contentEquals(byteArrayOf(0x51,0x5a,0x52,0x41,0x0d,0x0a,0x1a,0x0a))
    }

    private fun atomicWrite(destination: File, data: ByteArray) {
        val parent = destination.absoluteFile.parentFile ?: error("Invalid signature destination")
        require(parent.mkdirs() || parent.isDirectory) { "Cannot create signature directory" }
        require(!destination.exists() || destination.isFile) { "Signature destination is not a regular file" }
        val temp = File.createTempFile(".qamzor-signature-", ".tmp", parent)
        try {
            temp.writeBytes(data)
            try {
                java.nio.file.Files.move(temp.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(temp.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temp.delete() }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
