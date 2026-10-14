package org.pocketlab.puppy.runtime

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.system.Os
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
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
            _state.value = result.getOrElse { State.Failed(it.message ?: it.javaClass.simpleName) }
            _state.value
        }
    }

    private fun installLocked(): State {
        _state.value = State.Verifying
        val manifest = manifest() ?: return State.Unavailable
        val prefix = env.prefix.canonicalFile
        val expectedPrefix = manifest.getString("prefix")
        if (expectedPrefix != prefix.path) {
            throw IOException("Runtime was built for $expectedPrefix but this app uses ${prefix.path}")
        }
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

                val staging = File(env.filesDir, "usr-staging")
                staging.deleteRecursively()
                staging.mkdirs()
                try {
                    extract(staging)
                    _state.value = State.Activating
                    val old = File(env.filesDir, "usr-old")
                    old.deleteRecursively()
                    if (prefix.exists() && !prefix.renameTo(old)) throw IOException("Could not replace the old runtime")
                    if (!staging.renameTo(prefix)) {
                        old.renameTo(prefix)
                        throw IOException("Could not activate the runtime")
                    }
                    old.deleteRecursively()
                } catch (e: Throwable) {
                    staging.deleteRecursively()
                    throw e
                }
                smokeTest()
                marker.writeText(revision)
            }
        }
        return State.Ready(revision)
    }

    private fun extract(staging: File) {
        val root = staging.canonicalPath + File.separator
        val links = ArrayList<Pair<String, String>>()
        var count = 0
        context.assets.open(assetZip).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (name == "SYMLINKS.txt") {
                        zip.bufferedReader().readLines().forEach { line ->
                            val parts = line.split('←')
                            if (parts.size == 2) links += parts[0] to parts[1]
                        }
                        continue
                    }
                    val target = File(staging, name)
                    if (!target.canonicalPath.startsWith(root) && target.canonicalPath + File.separator != root) {
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
        for ((linkTarget, linkPath) in links) {
            val link = File(staging, linkPath)
            if (!link.canonicalFile.parentFile!!.canonicalPath.startsWith(staging.canonicalPath)) {
                throw IOException("Unsafe link in runtime archive: $linkPath")
            }
            // Absolute targets must stay inside the final PREFIX; relative ones inside the tree.
            val resolved = if (linkTarget.startsWith("/")) File(linkTarget)
            else File(link.parentFile, linkTarget)
            val prefixPath = env.prefix.canonicalPath
            val ok = if (linkTarget.startsWith("/")) resolved.path.startsWith(prefixPath) || resolved.path.startsWith("/system/")
            else resolved.canonicalPath.startsWith(staging.canonicalPath)
            if (!ok) throw IOException("Unsafe link target in runtime archive: $linkPath")
            link.parentFile?.mkdirs()
            Os.symlink(linkTarget, link.path)
        }
        _state.value = State.Extracting(count)
    }

    private fun isExecutable(name: String) =
        name.startsWith("bin/") || name.startsWith("libexec") || name.startsWith("lib/apt/apt-helper") ||
            name.startsWith("lib/apt/methods")

    /** Runs the activated shell once; files that need the final PREFIX only work after activation. */
    private fun smokeTest() {
        val sh = File(env.prefix, "bin/sh")
        val process = ProcessBuilder(sh.path, "-c", "echo puppy-ok")
            .directory(env.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment()["PATH"] = "${env.prefix.path}/bin:/system/bin"
                environment()["PREFIX"] = env.prefix.path
                environment()["LD_LIBRARY_PATH"] = "${env.prefix.path}/lib"
            }
            .start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0 || !out.contains("puppy-ok")) {
            throw IOException("The runtime shell did not start: ${out.take(200)}")
        }
    }

    /** Removes the runtime but keeps HOME. Used by the explicit repair action. */
    suspend fun uninstall() = withContext(Dispatchers.IO) {
        mutex.withLock {
            marker.delete()
            env.prefix.deleteRecursively()
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
        private const val SPACE_FACTOR = 4
    }
}
