package eu.kanade.tachiyomi.extension.ar.mangatek

import java.security.MessageDigest
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// v103: in-module port of the keiyoushi.utils Crypto.kt helpers this module
// needs (sha256, aesGcm, aesDecrypt). The MHExtensions base core predates the
// upstream core file that provides them, which is why CI failed with
// "Unresolved reference 'sha256' / 'aesDecrypt' / 'aesGcm'" while the rest of
// keiyoushi.utils (decodeHex, readUShortBigEndian, parseAs, ...) resolved fine.
// Deliberately kept in the module package (NOT keiyoushi.utils) so it can
// never collide with the helpers the base core already ships.

/**
 * Computes the SHA-256 digest of this [ByteArray].
 *
 * @return the SHA-256 digest bytes
 */
fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)

/**
 * Computes the SHA-256 digest of this string's UTF-8 bytes.
 *
 * @return the SHA-256 digest bytes
 */
fun String.sha256(): ByteArray = encodeToByteArray().sha256()

/**
 * Creates and initializes an AES [Cipher].
 */
private fun newAesCipher(
    transformation: String,
    key: ByteArray,
    params: AlgorithmParameterSpec? = null,
    encrypt: Boolean = false,
): Cipher = Cipher.getInstance(transformation).apply {
    init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), params)
}

/**
 * Creates and initializes an AES [Cipher] in GCM mode.
 *
 * @param key the AES key bytes
 * @param iv the initialization vector bytes
 * @param tagLength the authentication tag length in bits, defaults to 128
 * @param encrypt true for encryption, false for decryption
 */
fun aesGcm(
    key: ByteArray,
    iv: ByteArray,
    tagLength: Int = 128,
    encrypt: Boolean = false,
): Cipher = newAesCipher(
    transformation = "AES/GCM/NoPadding",
    key = key,
    params = GCMParameterSpec(tagLength, iv),
    encrypt = encrypt,
)

/**
 * Decrypts in-memory [ByteArray] using the specified [Cipher].
 */
fun ByteArray.aesDecrypt(cipher: Cipher): ByteArray = cipher.doFinal(this)
