/*
 * The iOS app's Kotlin: what the desktop's desktopApp is to the desktop — the audio engine, the
 * wiring of the shared pages, and the entry point the Swift shell in iosApp/ calls. It builds the
 * one framework the app links, BitChordKit, which carries :sharedUi and :shared with it.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The version Android ships as, for the places a server tells clients apart by it: an addon host
// that only serves BitChord 1.x refuses "v0.1.0" outright, whatever the bundle says.
val generateIosVersion = tasks.register("generateIosVersion") {
    val version = rootProject.extra["bitchordVersionName"] as String
    val out = layout.buildDirectory.dir("generated/version")
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/music/bitchord/ios/BitChordVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package com.music.bitchord.ios\n\ninternal const val BITCHORD_VERSION = \"$version\"\n")
    }
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "BitChordKit"
            isStatic = true
            // A call into a library built against another version fails the build here, not at
            // runtime on someone's phone.
            freeCompilerArgs += "-Xpartial-linkage-loglevel=ERROR"
        }
    }

    sourceSets {
        iosMain { kotlin.srcDir(generateIosVersion) }
        iosMain.dependencies {
            implementation(project(":sharedUi"))
            // Artwork over the Ktor client the rest of the app uses (Darwin underneath).
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.4.0")
        }
    }
}
