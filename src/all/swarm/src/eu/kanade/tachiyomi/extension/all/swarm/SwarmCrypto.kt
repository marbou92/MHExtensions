package eu.kanade.tachiyomi.extension.all.swarm

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side payload encryption for Swarm's reader endpoint (`POST /api/sp`).
 *
 * Reverse-engineered from the site bundle (chunk `32bx-yqtn5yus.js`) and live-verified
 * against the production API during the v26 recon:
 *
 * - The request body is `{"payload":"<hex iv>:<hex ciphertext>"}`.
 * - The ciphertext is AES-256-CBC / PKCS7 over a compact JSON object
 *   `{"chapterId":"<id>","nocache":false,"clean":false,"force":false}`.
 * - The key is SHA-256 of the ASCII string [SECRET] (a 64-hex-char constant embedded in the
 *   site JS — used directly as an ASCII seed, NOT decoded as hex bytes).
 * - The IV is 16 random bytes, prepended to the ciphertext as lowercase hex, separated by `:`.
 *
 * The server answers with a MangaDex@Home-style response
 * `{baseUrl:"/api/secure-proxy/<tok>", chapter:{data:[...page urls...], dataSaver:[...]}}`.
 *
 * Only `javax.crypto` / `java.security` are used — no external dependencies.
 */
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
