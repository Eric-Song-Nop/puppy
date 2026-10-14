package org.pocketlab.puppy.session.ssh

import java.io.StringWriter
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class SshKeysTest {
    companion object {
        @BeforeClass @JvmStatic
        fun setup() = SshSecurity.init()
    }

    private fun pem(obj: Any): String = StringWriter().also { sw -> JcaPEMWriter(sw).use { it.writeObject(obj) } }.toString()

    @Test
    fun generatedEd25519KeyParsesAndMatchesFingerprint() {
        val key = SshKeyGenerator.ed25519("test@puppy")
        assertTrue(key.privateKeyPem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(key.publicKey.startsWith("ssh-ed25519 AAAA"))
        assertTrue(key.publicKey.endsWith(" test@puppy"))

        val parsed = SshKeyImporter.parse(key.privateKeyPem) as KeyParseResult.Ok
        assertFalse(parsed.encrypted)
        assertEquals("ed25519", SshKeyCodec.shortType(parsed.publicKey))
        assertEquals(key.fingerprint, SshKeyCodec.fingerprint(parsed.publicKey))
        assertEquals(key.publicKey, SshKeyCodec.openSshPublicKey(parsed.publicKey, "test@puppy"))
    }

    @Test
    fun fingerprintIsOpenSshSha256Format() {
        val key = SshKeyGenerator.ed25519("")
        val blob = Base64.getDecoder().decode(key.publicKey.split(' ')[1])
        val expected = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
        assertEquals(expected, SshKeyCodec.fingerprint(blob))
        assertEquals(50, expected.length) // "SHA256:" + 43 chars, no padding
    }

    @Test
    fun rsaPkcs8KeyParses() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val parsed = SshKeyImporter.parse(pem(pair.private)) as KeyParseResult.Ok
        assertEquals("rsa", SshKeyCodec.shortType(parsed.publicKey))
        assertEquals(SshKeyCodec.fingerprint(pair.public), SshKeyCodec.fingerprint(parsed.publicKey))
    }

    @Test
    fun ecdsaKeyParses() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val parsed = SshKeyImporter.parse(pem(pair)) as KeyParseResult.Ok
        assertEquals("ecdsa", SshKeyCodec.shortType(parsed.publicKey))
    }

    @Test
    fun encryptedKeyNeedsTheRightPassphrase() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val encryptor = JcePEMEncryptorBuilder("AES-128-CBC").setSecureRandom(SecureRandom()).build("secret".toCharArray())
        val text = pem(JcaMiscPEMGenerator(pair, encryptor))
        assertTrue(text.contains("ENCRYPTED"))

        assertEquals(KeyParseResult.NeedsPassphrase, SshKeyImporter.parse(text))
        assertEquals(KeyParseResult.NeedsPassphrase, SshKeyImporter.parse(text, "wrong".toCharArray()))
        val ok = SshKeyImporter.parse(text, "secret".toCharArray()) as KeyParseResult.Ok
        assertTrue(ok.encrypted)
    }

    @Test
    fun garbageIsRejected() {
        assertTrue(SshKeyImporter.parse("hello world") is KeyParseResult.Invalid)
        assertTrue(SshKeyImporter.parse("x".repeat(SshKeyImporter.MAX_KEY_BYTES + 1)) is KeyParseResult.Invalid)
    }
}
