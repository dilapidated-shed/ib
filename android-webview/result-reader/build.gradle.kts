plugins {
    id("com.android.application")
}

val stableTestKeystorePath = providers.environmentVariable("IB_TEST_KEYSTORE").orNull
val stableTestKeystorePassword = providers.environmentVariable("IB_TEST_KEYSTORE_PASSWORD").orNull
    ?: "wegert-debug"
val stableTestKeyPassword = providers.environmentVariable("IB_TEST_KEY_PASSWORD").orNull
    ?: stableTestKeystorePassword
val stableTestKeyAlias = providers.environmentVariable("IB_TEST_KEY_ALIAS").orNull
    ?: "wegert-debug"
val ibSourceHead = providers.environmentVariable("IB_SOURCE_HEAD").orNull ?: "unknown"

android {
    namespace = "org.isomorphisms.ib.resultreader"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "org.isomorphisms.ib.resultreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "IB_SOURCE_HEAD", "\"$ibSourceHead\"")
    }

    signingConfigs {
        stableTestKeystorePath?.let { keystorePath ->
            create("stableTest") {
                storeFile = rootProject.file(keystorePath)
                storePassword = stableTestKeystorePassword
                keyAlias = stableTestKeyAlias
                keyPassword = stableTestKeyPassword
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.findByName("stableTest")
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.register("verifyResultReaderBoundary") {
    dependsOn("assembleDebug")
    doLast {
        val implementation = file("src/main/java/org/isomorphisms/ib/resultreader/ResultReaderActivity.java")
            .readText()
        val manifest = file("src/main/AndroidManifest.xml").readText()

        check(implementation.contains("openFileDescriptor")) {
            "The reader must obtain the result through ContentResolver/PFD."
        }
        check(implementation.contains("query(")) {
            "The reader must record provider/caller process metadata."
        }
        check(implementation.contains("read_twice")) {
            "The reader must exercise two independent descriptors."
        }
        check(manifest.contains("org.isomorphisms.ib.webview.results")) {
            "The reader must be scoped to the issue #84 provider authority."
        }

        val liveImplementation = file(
            "src/main/java/org/isomorphisms/ib/resultreader/LiveChannelActivity.java"
        ).readText()
        check(liveImplementation.contains("openFileDescriptor(uri, \"rw\")")) {
            "The live reader must receive the channel through a read-write PFD."
        }
        check(liveImplementation.contains("checkError()")) {
            "The live reader must distinguish peer close/error when possible."
        }
        check(liveImplementation.contains("caller-shim=separate-android-app")) {
            "The live-channel result must identify the Android shim boundary."
        }
        check(manifest.contains("org.isomorphisms.ib.webview.live")) {
            "The live reader must be scoped to the live provider authority."
        }

        val apks = fileTree("build/outputs/apk/debug") { include("*.apk") }.files
        check(apks.size == 1) { "Expected exactly one reader debug APK, found ${apks.size}." }
    }
}
