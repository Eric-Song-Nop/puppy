package org.pocketlab.puppy.data.vault

import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VaultTest {
    private class MemoryKeys : VaultKeySource {
        var key: SecretKey? = null
        override fun key(create: Boolean): SecretKey? {
            if (key == null && create) key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            return key
        }

        override fun delete() {
            key = null
        }
    }

    private lateinit var dir: File
    private lateinit var keys: MemoryKeys
    private lateinit var vault: Vault

    @Before
    fun setup() {
        dir = Files.createTempDirectory("vault").toFile()
        keys = MemoryKeys()
        vault = Vault(dir, keys)
    }

    @Test
    fun roundTrip() {
        vault.put("identity-1.key", "secret key".toByteArray())
        assertTrue(vault.exists("identity-1.key"))
        assertArrayEquals("secret key".toByteArray(), vault.get("identity-1.key"))
        // Ciphertext on disk does not contain the plaintext.
        assertFalse(String(File(dir, "identity-1.key").readBytes(), Charsets.ISO_8859_1).contains("secret key"))
    }

    @Test
    fun charsRoundTripAndZeroInput() {
        val secret = "pässwörd".toCharArray()
        vault.putChars("host-1.pw", secret)
        assertTrue(secret.all { it == '\u0000' })
        assertEquals("pässwörd", String(vault.getChars("host-1.pw")))
    }

    @Test(expected = VaultException.NotFound::class)
    fun missingEntry() {
        vault.get("host-9.pw")
    }

    @Test(expected = VaultException.Corrupt::class)
    fun entriesAreBoundToTheirName() {
        vault.put("host-1.pw", "a".toByteArray())
        File(dir, "host-1.pw").copyTo(File(dir, "host-2.pw"))
        vault.get("host-2.pw")
    }

    @Test(expected = VaultException.KeyInvalidated::class)
    fun lostKeyIsReported() {
        vault.put("host-1.pw", "a".toByteArray())
        keys.key = null
        vault.get("host-1.pw")
    }

    @Test(expected = VaultException.Corrupt::class)
    fun replacedKeyIsReportedAsDamaged() {
        vault.put("host-1.pw", "a".toByteArray())
        keys.key = null
        keys.key(create = true)
        vault.get("host-1.pw")
    }

    @Test
    fun resetRemovesEverything() {
        vault.put("host-1.pw", "a".toByteArray())
        vault.reset()
        assertFalse(vault.exists("host-1.pw"))
        assertEquals(null, keys.key)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathNames() {
        vault.put("../evil", "a".toByteArray())
    }
}
