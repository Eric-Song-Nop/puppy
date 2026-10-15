package org.pocketlab.puppy.runtime

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePathsTest {
    @Test fun deleteTreeDoesNotFollowLinks() {
        val root = Files.createTempDirectory("rt").toFile()
        val home = root.resolve("home").apply { mkdirs() }
        val keep = home.resolve("notes.txt").apply { writeText("keep") }
        val prefix = root.resolve("usr").apply { mkdirs() }
        prefix.resolve("bin").mkdirs()
        Files.createSymbolicLink(prefix.resolve("bin/home-link").toPath(), home.toPath())
        RuntimeManager.deleteTree(prefix)
        assertFalse(prefix.exists())
        assertTrue("HOME content must survive", keep.exists())
        root.deleteRecursively()
    }

    @Test fun insideComparesComponents() {
        val staging = Paths.get("/data/data/p/files/usr-staging")
        assertTrue(RuntimeManager.inside(staging.resolve("bin/sh"), staging))
        assertFalse(RuntimeManager.inside(staging, staging))
        // Prefix-of-name is not containment.
        assertFalse(RuntimeManager.inside(Paths.get("/data/data/p/files/usr-staging-x/a"), staging))
        // .. is normalized away.
        assertFalse(RuntimeManager.inside(Paths.get("/data/data/p/files/usr/../home/x"), Paths.get("/data/data/p/files/usr")))
        assertFalse(RuntimeManager.inside(staging.resolve("../../etc"), staging))
    }

    @Test fun symlinkTargetsUnderTheRemappedPackageMove() {
        val from = "/data/data/com.termux"
        val to = "/data/data/org.pocketlab.puppy"
        assertEquals("$to/files/usr/bin/bzless", RuntimeManager.remapLinkTarget("$from/files/usr/bin/bzless", from, to))
        assertEquals("./bin/ls", RuntimeManager.remapLinkTarget("./bin/ls", from, to))
        assertEquals("/data/data/com.termux.api/x", RuntimeManager.remapLinkTarget("/data/data/com.termux.api/x", from, to))
        assertEquals("$from/files/x", RuntimeManager.remapLinkTarget("$from/files/x", null, to))
    }
}
