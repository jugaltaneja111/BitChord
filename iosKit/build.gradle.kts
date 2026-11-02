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
// The commit too: iOS builds aren't numbered releases, so the update check asks whether the
// ios-latest build was made from a different commit than this one. Blank outside CI.
val generateIosVersion = tasks.register("generateIosVersion") {
    val version = rootProject.extra["bitchordVersionName"] as String
    val commit = providers.environmentVariable("GITHUB_SHA").orElse("").get()
    val out = layout.buildDirectory.dir("generated/version")
    inputs.property("version", version)
    inputs.property("commit", commit)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/music/bitchord/ios/BitChordVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package com.music.bitchord.ios\n\n" +
                "internal const val BITCHORD_VERSION = \"$version\"\n" +
                "internal const val BITCHORD_COMMIT = \"$commit\"\n",
        )
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
            // Artist signatures are served as SVG first.
            implementation("io.coil-kt.coil3:coil-svg:3.4.0")
        }
    }
}
