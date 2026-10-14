package org.pocketlab.puppy.session.local

/** JNI bindings for pty and child process management (native/src/pty.zig). */
internal object Pty {
    init {
        System.loadLibrary("puppy")
    }

    /**
     * Starts [path] on a new pty and returns the master fd, or -1. The child's
     * pid is stored in `outPid[0]`; it leads a new session and process group.
     */
    @JvmStatic external fun spawn(
        path: ByteArray, argv: Array<ByteArray>, env: Array<ByteArray>, cwd: ByteArray?,
        rows: Int, cols: Int, widthPx: Int, heightPx: Int, outPid: IntArray,
    ): Int

    @JvmStatic external fun setWindowSize(fd: Int, rows: Int, cols: Int, widthPx: Int, heightPx: Int)

    /** Blocks until [pid] exits; returns its exit code, or -signal. */
    @JvmStatic external fun waitFor(pid: Int): Int

    @JvmStatic external fun signalGroup(pid: Int, signal: Int)
    @JvmStatic external fun close(fd: Int)
}
