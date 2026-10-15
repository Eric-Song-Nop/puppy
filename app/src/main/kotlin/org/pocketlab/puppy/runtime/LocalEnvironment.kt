package org.pocketlab.puppy.runtime

import android.content.Context
import java.io.File
import org.pocketlab.puppy.session.local.LocalCommand

/**
 * Builds the command and environment for a local shell.
 *
 * Uses the bundled runtime under `files/usr` when it is installed; otherwise
 * falls back to Android's `/system/bin/sh` so local sessions still work
 * while the runtime is missing or being repaired.
 */
class LocalEnvironment(private val context: Context) {
    val filesDir: File = context.filesDir

    /**
     * Runtime binaries hard-code `/data/data/<applicationId>/files/usr`
     * (design §4.2); use that spelling when it is this app's files dir.
     */
    val prefix: File = File("/data/data/${context.packageName}/files").let { canonical ->
        if (sameFile(canonical, filesDir)) File(canonical, "usr") else File(filesDir, "usr")
    }
    val home = File(prefix.parentFile, "home")
    val tmp = File(prefix, "tmp")

    fun runtimeReady(): Boolean = File(prefix, "bin/sh").canExecute() && File(filesDir, RUNTIME_READY_MARKER).exists()

    fun shellCommand(cwd: String? = null): LocalCommand {
        home.mkdirs()
        val env = LinkedHashMap<String, String>()
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["HOME"] = home.absolutePath
        env["LANG"] = "en_US.UTF-8"
        env["ANDROID_DATA"] = System.getenv("ANDROID_DATA") ?: "/data"
        env["ANDROID_ROOT"] = System.getenv("ANDROID_ROOT") ?: "/system"
        System.getenv("ANDROID_ART_ROOT")?.let { env["ANDROID_ART_ROOT"] = it }
        System.getenv("ANDROID_I18N_ROOT")?.let { env["ANDROID_I18N_ROOT"] = it }
        System.getenv("ANDROID_TZDATA_ROOT")?.let { env["ANDROID_TZDATA_ROOT"] = it }
        System.getenv("BOOTCLASSPATH")?.let { env["BOOTCLASSPATH"] = it }
        System.getenv("DEX2OATBOOTCLASSPATH")?.let { env["DEX2OATBOOTCLASSPATH"] = it }
        System.getenv("EXTERNAL_STORAGE")?.let { env["EXTERNAL_STORAGE"] = it }

        val dir = cwd?.takeIf { File(it).isDirectory } ?: home.absolutePath
        return if (runtimeReady()) {
            tmp.mkdirs()
            env["PREFIX"] = prefix.absolutePath
            env["PATH"] = "${prefix.absolutePath}/bin:/system/bin:/system/xbin"
            env["TMPDIR"] = tmp.absolutePath
            env["SHELL"] = shellPath()
            env["PUPPY_VERSION"] = org.pocketlab.puppy.BuildConfig.VERSION_NAME
            env.putAll(remapEnv())
            LocalCommand(shellPath(), listOf("-l"), env, dir)
        } else {
            env["PATH"] = "/system/bin:/system/xbin"
            env["TMPDIR"] = context.cacheDir.absolutePath
            env["SHELL"] = SYSTEM_SHELL
            LocalCommand(SYSTEM_SHELL, listOf("-l"), env, dir)
        }
    }

    /** Set when the installed runtime was built for another package (official Termux). */
    val remapMarker = File(filesDir, "runtime.remap")

    /** The path prefix the runtime's binaries have compiled in, or null if built for us. */
    fun remapFrom(): String? = remapMarker.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /** Puppy's own data dir spelled like [prefix]: `/data/data/<applicationId>`. */
    val remapTo: String get() = prefix.parentFile!!.parentFile!!.path

    /**
     * Environment for running runtime programs: loads libpuppy_remap.so so
     * paths compiled for another package resolve inside Puppy, and points
     * the dynamic linker at PREFIX/lib (it reads RUNPATH itself, outside libc).
     */
    fun remapEnv(): Map<String, String> {
        val from = remapFrom() ?: return emptyMap()
        return mapOf(
            "LD_PRELOAD" to File(context.applicationInfo.nativeLibraryDir, "libpuppy_remap.so").path,
            "LD_LIBRARY_PATH" to File(prefix, "lib").path,
            "PUPPY_REMAP_FROM" to from,
            "PUPPY_REMAP_TO" to remapTo,
        )
    }

    private fun shellPath(): String {
        val bash = File(prefix, "bin/bash")
        return if (bash.canExecute()) bash.absolutePath else File(prefix, "bin/sh").absolutePath
    }

    companion object {
        /**
         * True if both paths are the same directory. `/data/data` and
         * `/data/user/0` are one directory through a mount, not a symlink,
         * so canonical paths differ; compare device and inode instead.
         */
        fun sameFile(a: File, b: File): Boolean = runCatching {
            val x = android.system.Os.stat(a.path)
            val y = android.system.Os.stat(b.path)
            x.st_dev == y.st_dev && x.st_ino == y.st_ino
        }.getOrDefault(false)

        const val SYSTEM_SHELL = "/system/bin/sh"
        const val RUNTIME_READY_MARKER = "runtime.ready"
    }
}
