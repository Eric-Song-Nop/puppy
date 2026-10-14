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
    val prefix = File(filesDir, "usr")
    val home = File(filesDir, "home")
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
            LocalCommand(shellPath(), listOf("-l"), env, dir)
        } else {
            env["PATH"] = "/system/bin:/system/xbin"
            env["TMPDIR"] = context.cacheDir.absolutePath
            env["SHELL"] = SYSTEM_SHELL
            LocalCommand(SYSTEM_SHELL, listOf("-l"), env, dir)
        }
    }

    private fun shellPath(): String {
        val bash = File(prefix, "bin/bash")
        return if (bash.canExecute()) bash.absolutePath else File(prefix, "bin/sh").absolutePath
    }

    companion object {
        const val SYSTEM_SHELL = "/system/bin/sh"
        const val RUNTIME_READY_MARKER = "runtime.ready"
    }
}
