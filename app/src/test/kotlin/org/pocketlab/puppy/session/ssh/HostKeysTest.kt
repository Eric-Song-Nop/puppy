package org.pocketlab.puppy.session.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pocketlab.puppy.data.KnownHost

class HostKeysTest {
    private fun known(algorithm: String, key: String) =
        KnownHost(host = "example.com", port = 22, algorithm = algorithm, publicKey = key, fingerprint = "SHA256:x")

    @Test
    fun unknownWhenNothingStored() {
        assertEquals(HostKeyDecision.Unknown, HostKeys.decide(emptyList(), "ssh-ed25519", "AAA"))
    }

    @Test
    fun unknownWhenOnlyOtherAlgorithmsStored() {
        assertEquals(HostKeyDecision.Unknown, HostKeys.decide(listOf(known("ssh-rsa", "R")), "ssh-ed25519", "E"))
    }

    @Test
    fun matchWhenSameKey() {
        val stored = listOf(known("ssh-rsa", "R"), known("ssh-ed25519", "E"))
        assertEquals(HostKeyDecision.Match, HostKeys.decide(stored, "ssh-ed25519", "E"))
    }

    @Test
    fun mismatchWhenKeyChanged() {
        val previous = known("ssh-ed25519", "OLD")
        val decision = HostKeys.decide(listOf(previous), "ssh-ed25519", "NEW")
        assertEquals(HostKeyDecision.Mismatch(previous), decision)
    }

    @Test
    fun hostValidation() {
        assertTrue(HostValidation.validate("Box", "example.com", "22", "me").isValid)
        assertTrue(HostValidation.validate("v6", "fe80::1%wlan0", "2222", "me").isValid)

        val empty = HostValidation.validate("", "", "", "")
        assertEquals("Required", empty.label)
        assertEquals("Required", empty.hostname)
        assertEquals("Required", empty.port)
        assertEquals("Required", empty.username)

        assertFalse(HostValidation.validate("a", "bad host", "22", "me").isValid)
        assertEquals("Port must be 1–65535", HostValidation.validate("a", "h", "0", "me").port)
        assertEquals("Port must be 1–65535", HostValidation.validate("a", "h", "70000", "me").port)
        assertFalse(HostValidation.validate("a", "h", "22", "me@x").isValid)
    }
}
