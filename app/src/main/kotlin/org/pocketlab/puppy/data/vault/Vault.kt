package org.pocketlab.puppy.data.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Why a vault operation failed. Messages never contain secret material. */
sealed class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The entry does not exist (never saved, or deleted). */
    class NotFound(name: String) : VaultException("No vault entry \"$name\"")

    /**
     * The device key is gone or was invalidated (lock screen reset, restore
     * onto another device...). Stored secrets are unrecoverable; the user
     * must re-import keys and re-enter passwords.
     */
    class KeyInvalidated(cause: Throwable? = null) :
        VaultException("The vault key is no longer valid; re-import keys and passwords", cause)

    /** The entry exists but cannot be read. */
    class Corrupt(name: String, cause: Throwable? = null) : VaultException("Vault entry \"$name\" is damaged", cause)
}

/** Supplies the AES key; the Android implementation lives in AndroidKeyStore. */
interface VaultKeySource {
    /** Returns the existing key, creating it if [create] and none exists; null if absent. */
    fun key(create: Boolean): SecretKey?
    fun delete()
}

/**
 * Encrypts private keys and saved passwords at rest (design §9.2).
 *
 * Each entry is a file `noBackupFilesDir/vault/<name>` holding
 * `version(1) || iv(12) || AES-256-GCM(ciphertext+tag)`, with the entry name
 * bound in as associated data so files can't be swapped. The key is held
 * by AndroidKeyStore without a user-authentication requirement.
 *
 * Same-UID local programs are not isolated from this (design §9.2).
 */
class Vault(private val dir: File, private val keys: VaultKeySource) {

    fun exists(name: String) = file(name).isFile

    fun put(name: String, plaintext: ByteArray) {
        val key = try {
            keys.key(create = true) ?: throw VaultException.KeyInvalidated()
        } catch (e: VaultException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw VaultException.KeyInvalidated(e)
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw VaultException.KeyInvalidated(e)
        }
        cipher.updateAAD(name.toByteArray())
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        dir.mkdirs()
        val out = ByteBuffer.allocate(1 + iv.size + ct.size).put(VERSION).put(iv).put(ct).array()
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file(name))) {
            tmp.delete()
            throw VaultException.Corrupt(name)
        }
    }

    fun get(name: String): ByteArray {
        val f = file(name)
        if (!f.isFile) throw VaultException.NotFound(name)
        val data = f.readBytes()
        if (data.size < 1 + IV_SIZE + 16 || data[0] != VERSION) throw VaultException.Corrupt(name)
        val key = try {
            keys.key(create = false) ?: throw VaultException.KeyInvalidated()
        } catch (e: VaultException) {
            throw e
        } catch (e: UnrecoverableKeyException) {
            throw VaultException.KeyInvalidated(e)
        } catch (e: GeneralSecurityException) {
            throw VaultException.KeyInvalidated(e)
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 1, IV_SIZE))
            cipher.updateAAD(name.toByteArray())
            return cipher.doFinal(data, 1 + IV_SIZE, data.size - 1 - IV_SIZE)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw VaultException.KeyInvalidated(e)
        } catch (e: AEADBadTagException) {
            // Wrong key (recreated after invalidation) or tampered file.
            throw VaultException.Corrupt(name, e)
        }
    }

    fun delete(name: String) {
        file(name).delete()
    }

    /** Stores a secret string; [secret] is zeroed afterwards. */
    fun putChars(name: String, secret: CharArray) {
        val bytes = toUtf8(secret)
        try {
            put(name, bytes)
        } finally {
            bytes.fill(0)
            secret.fill('\u0000')
        }
    }

    /** Reads a secret string; the caller should zero the result after use. */
    fun getChars(name: String): CharArray {
        val bytes = get(name)
        try {
            val cb = Charsets.UTF_8.decode(ByteBuffer.wrap(bytes))
            val out = CharArray(cb.remaining())
            cb.get(out)
            cb.array().fill('\u0000')
            return out
        } finally {
            bytes.fill(0)
        }
    }

    /** Removes every entry and the key. Used to recover from [VaultException.KeyInvalidated]. */
    fun reset() {
        dir.listFiles()?.forEach { it.delete() }
        keys.delete()
    }

    private fun file(name: String): File {
        require(NAME.matches(name)) { "invalid vault entry name" }
        return File(dir, name)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
        private val NAME = Regex("[A-Za-z0-9._-]{1,96}")

        fun identityEntry(identityId: Long) = "identity-$identityId.key"
        fun passwordEntry(hostId: Long) = "host-$hostId.pw"

        @Volatile private var instance: Vault? = null

        fun get(context: Context): Vault =
            instance ?: synchronized(this) {
                instance ?: Vault(File(context.noBackupFilesDir, "vault"), AndroidKeyStoreKeySource()).also { instance = it }
            }

        internal fun toUtf8(chars: CharArray): ByteArray {
            val bb = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
            val out = ByteArray(bb.remaining())
            bb.get(out)
            if (bb.hasArray()) bb.array().fill(0)
            return out
        }
    }
}

/** AES-256 key in AndroidKeyStore; never leaves secure hardware where available. */
class AndroidKeyStoreKeySource(private val alias: String = "puppy-vault-v1") : VaultKeySource {
    override fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun delete() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }
}
