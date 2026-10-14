package org.pocketlab.puppy.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A saved SSH host (design §9.1, §11). Secrets never live here. */
@Entity(tableName = "hosts")
data class HostProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Display name, separate from the connection address. */
    val label: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    /** Identity used for public key authentication, if any. */
    val identityId: Long? = null,
    /** Whether a password is kept in the vault for this host. */
    val savePassword: Boolean = false,
    val themeId: String? = null,
    /** Sent to the shell after it starts, e.g. `tmux new -A -s main`. */
    val initialCommand: String? = null,
    val group: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** Address shown under the label, e.g. `user@example.com:2222`. */
    val address: String
        get() = buildString {
            append(username).append('@').append(hostname)
            if (port != 22) append(':').append(port)
        }
}

/** A host key the user confirmed (design §9.1). */
@Entity(
    tableName = "known_hosts",
    indices = [Index(value = ["host", "port", "algorithm"], unique = true)],
)
data class KnownHost(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val host: String,
    val port: Int,
    /** SSH key type, e.g. `ssh-ed25519`. */
    val algorithm: String,
    /** Base64 of the SSH wire-format public key blob. */
    val publicKey: String,
    /** `SHA256:...` fingerprint as OpenSSH prints it. */
    val fingerprint: String,
    val confirmedAt: Long = System.currentTimeMillis(),
)

/**
 * A private key held by the vault. Only the public half and metadata are
 * stored here; the private key is an encrypted vault entry (design §9.2).
 */
@Entity(tableName = "identities")
data class Identity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** `ed25519`, `rsa` or `ecdsa`. */
    val type: String,
    /** OpenSSH one-line public key, e.g. `ssh-ed25519 AAAA... name`. */
    val publicKey: String,
    val fingerprint: String,
    /** Vault entry name holding the encrypted private key. */
    val encryptedFile: String,
    /** The stored key is itself passphrase-protected; the passphrase is asked on use. */
    val hasPassphrase: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
)
