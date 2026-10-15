import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.pocketlab.puppy"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "org.pocketlab.puppy"
        minSdk = 26
        // APK-first compatibility route; see docs/design.md §4.1. Executing
        // runtime binaries from the app data directory requires target < 29.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        // Keep in sync with native/build.zig.zon.
        buildConfigField("String", "GHOSTTY_REVISION", "\"9f13eb7526cc070a2b829f2aae41a44d5cce12bf\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // The runtime bootstrap is read with openFd and must stay uncompressed.
        noCompress += "zip"
    }

    packaging {
        // libpuppy_remap.so is loaded by shells via LD_PRELOAD from
        // nativeLibraryDir, so native libraries must be extracted.
        jniLibs.useLegacyPackaging = true
        resources.excludes += listOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/LICENSE.md", "META-INF/NOTICE.md")
    }

    lint {
        // targetSdk 28 is a deliberate choice (docs/design.md §4.1).
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

/** Builds libpuppy.so (Ghostty VT core, JNI and PTY) with the pinned Zig. */
abstract class ZigBuildTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:Input abstract val zig: Property<String>
    @get:Input abstract val ndkDir: Property<String>
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val minSdk: Property<Int>
    @get:Input abstract val release: Property<Boolean>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        for (abi in abis.get()) {
            val triple = when (abi) {
                "arm64-v8a" -> "aarch64-linux-android"
                "x86_64" -> "x86_64-linux-android"
                else -> error("unsupported ABI $abi")
            }
            val log = ByteArrayOutputStream()
            val result = exec.exec {
                workingDir = sourceDir.get().asFile
                environment("ANDROID_NDK_HOME", ndkDir.get())
                commandLine(
                    zig.get(), "build",
                    "-Dtarget=$triple.${minSdk.get()}",
                    "-Drelease=${release.get()}",
                    "--prefix-lib-dir", File(out, abi).absolutePath,
                    "--summary", "none",
                )
                standardOutput = log
                errorOutput = log
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0) throw GradleException("zig build failed for $abi:\n$log")
        }
    }
}

val zigBuild = tasks.register<ZigBuildTask>("zigBuild") {
    sourceDir.set(rootProject.layout.projectDirectory.dir("native"))
    zig.set(providers.environmentVariable("ZIG").orElse("zig"))
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
    abis.set(listOf("arm64-v8a"))
    minSdk.set(26)
    // The terminal core is too slow unoptimized, so debug APKs use release Zig too.
    release.set(true)
    outputDir.set(layout.buildDirectory.dir("zig/jniLibs"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(zigBuild, ZigBuildTask::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
