package org.pocketlab.puppy.runtime

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.system.Os
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Installs and checks the bundled local runtime (design §5.2).
 *
 * The APK carries `assets/runtime/bootstrap-<abi>.zip` in Termux bootstrap
 * format (files relative to PREFIX plus SYMLINKS.txt) and a manifest
 * `bootstrap-<abi>.json` with `prefix`, `sha256` and `revision`. The
 * bootstrap must be built for this app's PREFIX (see runtime/README.md).
 */
class RuntimeManager(private val context: Context) {

    sealed interface State {
        /** This build ships no runtime for the device ABI; local shells use /system/bin/sh. */
        data object Unavailable : State
        data object Missing : State
        data object Verifying : State
        data class Extracting(val files: Int) : State
        data object Activating : State
        data class Ready(val revision: String) : State
        data class Failed(val message: String) : State
    }

    private val env = LocalEnvironment(context)
    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()
    private val mutex = Mutex()

    private val abi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    private val assetZip = "runtime/bootstrap-$abi.zip"
    private val assetManifest = "runtime/bootstrap-$abi.json"
    private val marker = File(env.filesDir, LocalEnvironment.RUNTIME_READY_MARKER)

    /** Reads the current state without changing anything on disk. */
    fun refresh() {
        _state.value = when {
            env.runtimeReady() -> State.Ready(marker.readText().trim().ifEmpty { "unknown" })
            manifest() == null -> State.Unavailable
            else -> State.Missing
        }
    }

    private fun manifest(): JSONObject? = runCatching {
        context.assets.open(assetManifest).bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()

    /** Installs the bundled bootstrap. Safe to call repeatedly; HOME is never touched. */
    suspend fun install(): State = withContext(Dispatchers.IO) {
        mutex.withLock {
            refresh()
            if (_state.value is State.Ready || _state.value is State.Unavailable) return@withLock _state.value
            val result = runCatching { installLocked() }
            result.exceptionOrNull()?.let { android.util.Log.w(TAG, "runtime install failed", it) }
            _state.value = result.getOrElse { State.Failed(it.message ?: it.javaClass.simpleName) }
            _state.value
        }
    }

    private fun installLocked(): State {
        _state.value = State.Verifying
        val manifest = manifest() ?: return State.Unavailable
        val prefix = env.prefix
        val expectedPrefix = manifest.getString("prefix")
        // Official Termux bootstraps are built for com.termux and run through
        // libpuppy_remap.so; our own builds must match this app's data dir.
        val remapFrom = manifest.optString("remapFrom").ifEmpty { null }
        if (remapFrom != null) {
            if (expectedPrefix != "$remapFrom/files/usr") throw IOException("Runtime manifest is inconsistent")
        } else if (prefix.path != expectedPrefix || !LocalEnvironment.sameFile(File(expectedPrefix).parentFile!!, env.filesDir)) {
            // Secondary users and app clones get a different data dir (design §4.2).
            throw IOException("Runtime was built for $expectedPrefix but this app's data is in ${env.filesDir.path}")
        }
        if (remapFrom != null) env.remapMarker.writeText(remapFrom) else env.remapMarker.delete()
        val expectedSha = manifest.getString("sha256").lowercase()
        val revision = manifest.optString("revision", "unknown")

        val size = context.assets.openFd(assetZip).use { it.length }
        val free = StatFs(env.filesDir.path).availableBytes
        if (free < size * SPACE_FACTOR) throw IOException("Not enough free space to install the runtime")

        // Cross-process lock: a coroutine mutex alone can't stop a second process.
        RandomAccessFile(File(env.filesDir, "runtime.lock"), "rw").channel.use { channel ->
            channel.lock().use {
                val actual = context.assets.open(assetZip).use { sha256(it) }
                if (actual != expectedSha) throw IOException("Runtime archive is corrupt (checksum mismatch)")

                // Same directory spelling as PREFIX: /data/data and /data/user/0 are
                // different mounts, and rename() across them fails with EXDEV.
                val staging = File(prefix.parentFile, "usr-staging")
                staging.let(::deleteTree)
                staging.mkdirs()
                try {
                    extract(staging, remapFrom)
                    _state.value = State.Activating
                    val old = File(prefix.parentFile, "usr-old")
                    old.let(::deleteTree)
                    if (prefix.exists() && !prefix.renameTo(old)) throw IOException("Could not replace the old runtime")
                    if (!staging.renameTo(prefix)) {
                        old.renameTo(prefix)
                        throw IOException("Could not activate the runtime")
                    }
                    old.let(::deleteTree)
                } catch (e: Throwable) {
                    staging.let(::deleteTree)
                    throw e
                }
                runSecondStage()
                smokeTest()
                marker.writeText(revision)
            }
        }
        return State.Ready(revision)
    }

    private fun extract(staging: File, remapFrom: String?) {
        val links = ArrayList<Pair<String, String>>()
        var count = 0
        context.assets.open(assetZip).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (name == "SYMLINKS.txt") {
                        // readBytes stops at the entry's end without closing the zip stream.
                        zip.readBytes().decodeToString().lines().forEach { line ->
                            val parts = line.split('←')
                            if (parts.size == 2) links += parts[0] to parts[1]
                        }
                        continue
                    }
                    val target = File(staging, name)
                    if (!inside(target.toPath(), staging.toPath())) {
                        throw IOException("Unsafe path in runtime archive: $name")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                    if (isExecutable(name)) Os.chmod(target.path, "700".toInt(8))
                    if (++count % 200 == 0) _state.value = State.Extracting(count)
                }
            }
        }
        for ((rawTarget, linkPath) in links) {
            // The kernel resolves symlinks, so the remap library can't fix them.
            val linkTarget = remapLinkTarget(rawTarget, remapFrom, env.remapTo)
            val link = File(staging, linkPath)
            if (!inside(link.toPath(), staging.toPath())) {
                throw IOException("Unsafe link in runtime archive: $linkPath")
            }
            // Absolute targets must stay inside the final PREFIX (or /system);
            // relative ones inside the extracted tree. Compared as normalized paths.
            val ok = if (linkTarget.startsWith("/")) {
                val t = Paths.get(linkTarget)
                inside(t, env.prefix.toPath()) || inside(t, Paths.get("/system"))
            } else {
                inside(link.toPath().parent.resolve(linkTarget), staging.toPath())
            }
            if (!ok) throw IOException("Unsafe link target in runtime archive: $linkPath")
            link.parentFile?.mkdirs()
            Os.symlink(linkTarget, link.path)
        }
        _state.value = State.Extracting(count)
    }

    private fun isExecutable(name: String) =
        name.startsWith("bin/") || name.startsWith("libexec") || name.startsWith("lib/apt/apt-helper") ||
            name.startsWith("lib/apt/methods")

    /**
     * Newer Termux bootstraps finish package configuration in a second stage
     * that needs the final PREFIX; run it once after activation.
     */
    private fun runSecondStage() {
        val script = File(env.prefix, "etc").walkTopDown().maxDepth(6)
            .firstOrNull { it.name == "termux-bootstrap-second-stage.sh" } ?: return
        val bash = File(env.prefix, "bin/bash").takeIf { it.canExecute() } ?: File(env.prefix, "bin/sh")
        val (code, out) = run(listOf(bash.path, script.path), TimeUnit.MINUTES.toMillis(5))
        if (code != 0) throw IOException("Runtime setup failed: ${out.takeLast(300)}")
    }

    private fun runtimeEnv(): Map<String, String> = mapOf(
        "PATH" to "${env.prefix.path}/bin:/system/bin",
        "PREFIX" to env.prefix.path,
        "HOME" to env.home.path,
        "TMPDIR" to File(env.prefix, "tmp").path,
        "LANG" to "en_US.UTF-8",
    ) + env.remapEnv()

    /** Runs the activated shell once; files that need the final PREFIX only work after activation. */
    private fun smokeTest() {
        val sh = File(env.prefix, "bin/sh")
        val (code, out) = run(listOf(sh.path, "-c", "echo puppy-ok"), 20_000)
        if (code != 0 || !out.contains("puppy-ok")) {
            throw IOException("The runtime shell did not start: ${out.take(200)}")
        }
    }

    /**
     * Runs a runtime program with output going to a file, so a hung program
     * can't block us on a pipe; it is killed at the timeout. Returns
     * (exit code, or null on timeout) and its output.
     */
    private fun run(command: List<String>, timeoutMs: Long): Pair<Int?, String> {
        val log = File.createTempFile("runtime", ".log", context.cacheDir)
        try {
            val process = ProcessBuilder(command)
                .directory(env.filesDir)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .apply { environment().putAll(runtimeEnv()) }
                .start()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return null to log.readText()
            }
            return process.exitValue() to log.readText()
        } finally {
            log.delete()
        }
    }

    /** Removes the runtime but keeps HOME. Used by the explicit repair action. */
    suspend fun uninstall() = withContext(Dispatchers.IO) {
        mutex.withLock {
            RandomAccessFile(File(env.filesDir, "runtime.lock"), "rw").channel.use { channel ->
                channel.lock().use {
                    marker.delete()
                    deleteTree(env.prefix)
                }
            }
            refresh()
        }
    }

    private fun sha256(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "PuppyRuntime"
        private const val SPACE_FACTOR = 4

        /** Rewrites an absolute symlink target under [from] to the same path under [to]. */
        fun remapLinkTarget(target: String, from: String?, to: String): String =
            if (from != null && (target == from || target.startsWith("$from/"))) to + target.substring(from.length) else target

        /** True if [child] is strictly below [parent], compared component-wise after normalizing. */
        fun inside(child: Path, parent: Path): Boolean {
            val c = child.toAbsolutePath().normalize()
            val p = parent.toAbsolutePath().normalize()
            return c != p && c.startsWith(p)
        }

        /**
         * Deletes a tree without following symbolic links, so a link inside
         * PREFIX pointing at HOME can never make a repair delete user files.
         */
        fun deleteTree(file: File) {
            val path = file.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                file.listFiles()?.forEach(::deleteTree)
            }
            Files.deleteIfExists(path)
        }
    }
}
