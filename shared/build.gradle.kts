import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iPhone and the Apple-silicon simulator. Only commonMain reaches these so far: the code in
    // jvmSharedMain moves over as it stops depending on the JDK (see docs/IOS_PORT.md).
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "BitChordShared"
            isStatic = true
        }
    }

    // Android and desktop are both JVM, but the default hierarchy gives them no source set in
    // common.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // Compile-only: the two applications supply the runtime artifact themselves.
                compileOnly("com.microsoft.onnxruntime:onnxruntime:1.20.0")
                // The JVM half of the network stack (see commonMain for the rest).
                api("io.ktor:ktor-client-okhttp:3.5.2")
                api("org.jsoup:jsoup:1.22.2")
                // NewPipe, the JVM-only fallback for YouTube stream resolution. Compile-only
                // because the phone ships a trimmed jar of it.
                compileOnly("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
            }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        commonMain.dependencies {
            // Models in this module intentionally have no Android or Compose dependency.
            // The network stack the lyrics providers and the YouTube Music client run on, at the
            // phone's versions — every application calls the same code, so they resolve the same
            // libraries. Each platform adds its engine (OkHttp in jvmSharedMain, Darwin in
            // iosMain); shared code builds clients through appHttpClient.
            api("io.ktor:ktor-client-core:3.5.2")
            api("io.ktor:ktor-client-websockets:3.5.2")
            api("io.ktor:ktor-client-content-negotiation:3.5.2")
            api("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            // YouTube stream resolution, as the phone does it: InnerTubeX's client catalog and
            // cipher tiers. It is multiplatform and published for iOS too.
            api("com.github.MetrolistGroup.innertubex:innertubex:v0.7.0")
            // Locks for code shared with iOS, where the JVM's `synchronized` does not exist. On the
            // JVM these compile to plain `synchronized` blocks.
            implementation("org.jetbrains.kotlinx:atomicfu:0.33.0")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.5.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("com.squareup.okhttp3:mockwebserver:5.3.2")
        }
    }
}

android {
    namespace = "com.music.bitchord.shared"
    compileSdk = 36
}

// On CI, print what tests write: the live YouTube test's output is the diagnosis when it fails,
// and the job log is where it can be read.
if (System.getenv("CI") != null) {
    tasks.withType<AbstractTestTask>().configureEach {
        testLogging {
            showStandardStreams = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
