import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.parcelize)
}

group = "io.github.snd-r.komelia.domain.core"
version = "unspecified"

kotlin {
    android {
        namespace = "io.github.snd_r.komelia.domain.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }

    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")

    sourceSets {
        all {
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
            languageSettings.optIn("kotlin.ExperimentalUnsignedTypes")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
        }
        commonMain.dependencies {
            api(projects.komeliaDomain.komgaApi)
            api(projects.komeliaDomain.offline)
            api(projects.komeliaInfra.database.transaction)
            api(projects.komeliaInfra.imageDecoder.shared)
            api(projects.komeliaInfra.onnxruntime.api)

            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)

            implementation(libs.cache4k)
            api(libs.coil)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.filekit.core)
            api(libs.komf.client)
            api(libs.komga.client)
            api(libs.ktor.client.core)
            api(libs.ktor.client.content.negotiation)
            api(libs.ktor.client.encoding)
            api(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ksoup)
            implementation(libs.markdown)
            implementation(libs.reorderable)
            implementation(libs.richEditor.compose)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmTest.dependencies {
            val hostOs = when {
                System.getProperty("os.name").startsWith("Win") -> "windows"
                System.getProperty("os.name").startsWith("Linux") -> "linux"
                else -> error("Unsupported OS: ${System.getProperty("os.name")}")
            }
            val hostArch = when (val osArch = System.getProperty("os.arch")) {
                "x86_64", "amd64" -> "x64"
                "aarch64" -> "arm64"
                else -> error("Unsupported arch: $osArch")
            }
            implementation(
                "org.jetbrains.compose.desktop:desktop-jvm-$hostOs-$hostArch:${libs.versions.compose.multiplatform.get()}"
            )
        }

        androidMain.dependencies {
            implementation(libs.androidx.appcompat)
            implementation(libs.commons.compress)
            api(libs.ktor.client.okhttp)
            api(libs.logback.android)
            api(libs.okhttp)
            api(libs.okhttp.logging.interceptor)
            implementation(libs.slf4j.api)
            implementation(projects.komeliaInfra.imageDecoder.vips)
            implementation(projects.komeliaInfra.onnxruntime.jvm)
        }

        jvmMain.dependencies {
            implementation(libs.commons.compress)
            api(libs.directories)
            implementation(libs.java.keyring)
            implementation(libs.jbr.api)
            api(libs.ktor.client.okhttp)
            api(libs.logback.core)
            api(libs.logback.classic)
            api(libs.okhttp)
            api(libs.okhttp.logging.interceptor)
            implementation(libs.secret.service)
            implementation(libs.slf4j.api)
            implementation(projects.komeliaInfra.imageDecoder.vips)
            implementation(projects.komeliaInfra.onnxruntime.jvm)
        }
    }
}
