package eu.kanade.tachiyomi.extension.all.swarm

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object SwarmCrypto {

    /**
     * Seed string recovered from the site's reader chunk. SHA-256 of this ASCII string
     * is the raw 32-byte AES key (verified end-to-end against POST /api/sp → HTTP 200).
     */
    private const val SECRET = "e3bca6f9a7e64e369ddc450bb7ff295b3d78e7d4fcb148c10d10ae9385476a5f"

    private val key: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256").digest(SECRET.toByteArray(Charsets.US_ASCII))
    }

    private val random = SecureRandom()

    /**
     * Encrypts [plaintextJson] and returns the `"<hex iv>:<hex ciphertext>"` payload value
     * expected by `POST /api/sp`.
     */
    fun encryptPayload(plaintextJson: String): String {
        val iv = ByteArray(16).also(random::nextBytes)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintextJson.toByteArray(Charsets.UTF_8))

        return "${iv.toHex()}:${ciphertext.toHex()}"
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }
}
