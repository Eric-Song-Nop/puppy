package org.pocketlab.puppy.session.ssh

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pocketlab.puppy.data.AppDatabase
import org.pocketlab.puppy.data.Identity
import org.pocketlab.puppy.data.vault.Vault

/** Imports, generates and deletes SSH identities (vault + database). */
class IdentityRepository(
    context: Context,
    private val db: AppDatabase = AppDatabase.get(context),
    private val vault: Vault = Vault.get(context),
) {
    sealed interface ImportResult {
        class Imported(val identity: Identity) : ImportResult
        data object NeedsPassphrase : ImportResult
        class Invalid(val reason: String) : ImportResult
    }

    /**
     * Imports a private key file. Encrypted keys are verified with
     * [passphrase] and stored still encrypted; the passphrase is asked
     * again when connecting and is never stored.
     */
    suspend fun import(name: String, keyText: String, passphrase: CharArray?): ImportResult = withContext(Dispatchers.IO) {
        when (val parsed = SshKeyImporter.parse(keyText, passphrase)) {
            is KeyParseResult.Invalid -> ImportResult.Invalid(parsed.reason)
            KeyParseResult.NeedsPassphrase -> ImportResult.NeedsPassphrase
            is KeyParseResult.Ok -> ImportResult.Imported(
                store(
                    name = name,
                    type = SshKeyCodec.shortType(parsed.publicKey),
                    publicKey = SshKeyCodec.openSshPublicKey(parsed.publicKey, name),
                    fingerprint = SshKeyCodec.fingerprint(parsed.publicKey),
                    privateKey = keyText,
                    encrypted = parsed.encrypted,
                ),
            )
        }
    }

    /** Generates a new Ed25519 key, stored unencrypted in the vault. */
    suspend fun generateEd25519(name: String): Identity = withContext(Dispatchers.IO) {
        val key = SshKeyGenerator.ed25519(name)
        store(name, "ed25519", key.publicKey, key.fingerprint, key.privateKeyPem, encrypted = false)
    }

    private suspend fun store(
        name: String,
        type: String,
        publicKey: String,
        fingerprint: String,
        privateKey: String,
        encrypted: Boolean,
    ): Identity {
        val entry = "identity-" + UUID.randomUUID().toString() + ".key"
        val bytes = privateKey.toByteArray(Charsets.UTF_8)
        try {
            vault.put(entry, bytes)
        } finally {
            bytes.fill(0)
        }
        val identity = Identity(
            name = name.trim().ifEmpty { type },
            type = type,
            publicKey = publicKey,
            fingerprint = fingerprint,
            encryptedFile = entry,
            hasPassphrase = encrypted,
        )
        return try {
            identity.copy(id = db.identities().insert(identity))
        } catch (e: Exception) {
            vault.delete(entry)
            throw e
        }
    }

    suspend fun delete(identity: Identity) = withContext(Dispatchers.IO) {
        db.hosts().clearIdentity(identity.id)
        db.identities().delete(identity)
        vault.delete(identity.encryptedFile)
    }
}
