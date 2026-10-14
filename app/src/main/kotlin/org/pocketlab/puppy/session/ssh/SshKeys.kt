package org.pocketlab.puppy.session.ssh

import java.io.StringReader
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter

/** Public-key encodings as OpenSSH prints them. */
object SshKeyCodec {
    /** SSH wire-format blob of a public key. */
    fun blob(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData

    /** `SHA256:<base64 without padding>`, as `ssh-keygen -l` shows it. */
    fun fingerprint(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    fun fingerprint(key: PublicKey) = fingerprint(blob(key))

    /** SSH key type name such as `ssh-ed25519`. */
    fun algorithm(key: PublicKey): String = KeyType.fromKey(key).toString()

    /** One-line `authorized_keys` form: `<type> <base64> [comment]`. */
    fun openSshPublicKey(key: PublicKey, comment: String? = null): String {
        val line = algorithm(key) + " " + Base64.getEncoder().encodeToString(blob(key))
        return if (comment.isNullOrBlank()) line else "$line ${comment.trim()}"
    }

    /** Short type for display/storage: `ed25519`, `rsa`, `ecdsa`. */
    fun shortType(key: PublicKey): String = when (val a = algorithm(key)) {
        "ssh-ed25519" -> "ed25519"
        "ssh-rsa" -> "rsa"
        else -> if (a.startsWith("ecdsa")) "ecdsa" else a
    }
}

/** Result of parsing a private key file the user imported. */
sealed interface KeyParseResult {
    class Ok(val publicKey: PublicKey, val encrypted: Boolean) : KeyParseResult
    /** The key is encrypted and no (or a wrong) passphrase was given. */
    data object NeedsPassphrase : KeyParseResult
    class Invalid(val reason: String) : KeyParseResult
}

/**
 * Private key parsing through SSHJ's key providers, so a key that imports
 * here is one SSHJ can use when connecting.
 */
object SshKeyImporter {
    const val MAX_KEY_BYTES = 64 * 1024

    /** Creates a key provider for [text]; [passwords] is asked when the key is encrypted. */
    fun provider(text: String, passwords: PasswordFinder?): FileKeyProvider {
        SshSecurity.init()
        val format = KeyProviderUtil.detectKeyFileFormat(text, false)
        val kp: FileKeyProvider = when (format) {
            KeyFormat.OpenSSHv1 -> OpenSSHKeyV1KeyFile()
            KeyFormat.PKCS8 -> PKCS8KeyFile()
            KeyFormat.OpenSSH -> OpenSSHKeyFile()
            KeyFormat.PuTTY -> PuTTYKeyFile()
            else -> throw IllegalArgumentException("Unrecognized private key format")
        }
        if (passwords != null) kp.init(StringReader(text), passwords) else kp.init(StringReader(text))
        return kp
    }

    /**
     * Parses [text]. If the key is encrypted, [passphrase] is used to verify it
     * (and is not retained). The passphrase array is not modified.
     */
    fun parse(text: String, passphrase: CharArray? = null): KeyParseResult {
        if (text.length > MAX_KEY_BYTES) return KeyParseResult.Invalid("File is too large to be a private key")
        var asked = false
        val finder = object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray? {
                asked = true
                return passphrase?.copyOf()
            }

            override fun shouldRetry(resource: Resource<*>?) = false
        }
        return try {
            val kp = provider(text, finder)
            // Reading the private key forces full parsing and decryption.
            kp.private
            KeyParseResult.Ok(kp.public, encrypted = asked)
        } catch (e: IllegalArgumentException) {
            KeyParseResult.Invalid(e.message ?: "Unrecognized private key")
        } catch (e: Exception) {
            if (asked) KeyParseResult.NeedsPassphrase else KeyParseResult.Invalid("Not a supported private key")
        }
    }
}

/** A newly generated key pair in OpenSSH formats. */
class GeneratedKey(val privateKeyPem: String, val publicKey: String, val fingerprint: String)

object SshKeyGenerator {
    /** Generates an unencrypted Ed25519 key in `openssh-key-v1` format. */
    fun ed25519(comment: String): GeneratedKey {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = gen.generateKeyPair()
        val privateBlob = OpenSSHPrivateKeyUtil.encodePrivateKey(pair.private)
        val pem = java.io.StringWriter().also { sw ->
            PemWriter(sw).use { it.writeObject(PemObject("OPENSSH PRIVATE KEY", privateBlob)) }
        }.toString()
        privateBlob.fill(0)
        val publicBlob = OpenSSHPublicKeyUtil.encodePublicKey(pair.public)
        val line = "ssh-ed25519 " + Base64.getEncoder().encodeToString(publicBlob) +
            (if (comment.isBlank()) "" else " ${comment.trim()}")
        return GeneratedKey(pem, line, SshKeyCodec.fingerprint(publicBlob))
    }
}
