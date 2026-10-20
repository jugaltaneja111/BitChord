/*
 * The iOS app's Kotlin: what the desktop's desktopApp is to the desktop — the audio engine, the
 * wiring of the shared pages, and the entry point the Swift shell in iosApp/ calls. It builds the
 * one framework the app links, BitChordKit, which carries :sharedUi and :shared with it.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
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
        iosMain.dependencies {
            implementation(project(":sharedUi"))
            // Artwork over the Ktor client the rest of the app uses (Darwin underneath).
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.4.0")
        }
    }
}
