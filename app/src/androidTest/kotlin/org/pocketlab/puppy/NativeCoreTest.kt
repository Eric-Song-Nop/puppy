package org.pocketlab.puppy

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pocketlab.puppy.session.local.Pty
import org.pocketlab.puppy.terminal.NativeTerminal
import org.pocketlab.puppy.terminal.TerminalFrame

/** Gate G1 on a device: the Ghostty core and pty work through JNI. */
@RunWith(AndroidJUnit4::class)
class NativeCoreTest {
    private fun snapshot(h: Long, cols: Int, rows: Int): TerminalFrame {
        val buf = ByteBuffer.allocateDirect(NativeTerminal.frameCapacity(cols, rows)).order(ByteOrder.LITTLE_ENDIAN)
        val n = NativeTerminal.snapshot(h, buf, true)
        assertTrue("snapshot failed: $n", n > 0)
        return TerminalFrame.decode(buf, n)
    }

    @Test fun parsesSplitInputAndResizes() {
        val h = NativeTerminal.create(20, 4, 1000)
        try {
            val bytes = "\u001b[1;31mR\u001b[0m 中文 ok".toByteArray()
            // Feed one byte at a time: sequences and UTF-8 span writes.
            for (i in bytes.indices) NativeTerminal.feed(h, bytes, i, 1)
            val f = snapshot(h, 20, 4)
            assertEquals("R 中文 ok", f.lineText(0))
            assertTrue(f.attrs[0] and TerminalFrame.ATTR_BOLD != 0)
            assertTrue(NativeTerminal.resize(h, 40, 10, 10, 20))
            val g = snapshot(h, 40, 10)
            assertEquals(40, g.cols)
            assertEquals(10, g.rows)
        } finally {
            NativeTerminal.destroy(h)
        }
    }

    @Test fun answersDeviceAttributesAndEncodesKeys() {
        val h = NativeTerminal.create(20, 4, 1000)
        try {
            val ev = NativeTerminal.feed(h, "\u001b[c".toByteArray(), 0, 3)
            assertTrue(ev and NativeTerminal.EVENT_REPLIES != 0)
            assertNotNull(NativeTerminal.takeReplies(h))
            val ctrlC = NativeTerminal.encodeKey(h, "key_c".toByteArray(), "c".toByteArray(), 2, 0, 1, 'c'.code)
            assertEquals(listOf<Byte>(3), ctrlC!!.toList())
        } finally {
            NativeTerminal.destroy(h)
        }
    }

    @Test fun ptyRunsAShellAndReportsExitCode() {
        val pid = IntArray(1)
        val fd = Pty.spawn(
            "/system/bin/sh".toByteArray(),
            arrayOf("/system/bin/sh".toByteArray(), "-c".toByteArray(), "stty size; exit 7".toByteArray()),
            arrayOf("PATH=/system/bin".toByteArray()),
            null, 24, 80, 0, 0, pid,
        )
        assertTrue(fd >= 0)
        val pfd = android.os.ParcelFileDescriptor.adoptFd(fd)
        val out = StringBuilder()
        FileInputStream(pfd.fileDescriptor).use { input ->
            val buf = ByteArray(256)
            while (true) {
                val n = try { input.read(buf) } catch (_: java.io.IOException) { -1 }
                if (n <= 0) break
                out.append(String(buf, 0, n))
            }
        }
        assertEquals(7, Pty.waitFor(pid[0]))
        assertTrue("output was: $out", out.contains("24 80"))
    }
}
