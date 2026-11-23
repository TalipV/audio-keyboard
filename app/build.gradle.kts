import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.yazin.keyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.yazin.keyboard"
        minSdk = 29
        targetSdk = 36
        // CI passes the run number (-PversionCode=…), so every build installs as an update.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 7
        // Raised by hand on every app change (see "Versioning" in AGENTS.md); CI sets versionCode to its run number.
        versionName = "0.10.1"
    }

    // Release signing comes from keystore.properties (git-ignored: storeFile, storePassword, keyAlias, keyPassword)
    // or, in CI, from the KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD environment variables.
    // Without either, release builds are signed with the debug key, which is fine for sideloading your own build.
    val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use(::load) } }
        ?: System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { path ->
            Properties().apply {
                setProperty("storeFile", path)
                setProperty("storePassword", System.getenv("KEYSTORE_PASSWORD"))
                setProperty("keyAlias", System.getenv("KEY_ALIAS"))
                setProperty("keyPassword", System.getenv("KEY_PASSWORD"))
            }
        }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = rootProject.file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    buildFeatures { buildConfig = true }

    // Lets JVM tests construct small Android value classes (ExtractedText) used by the editor.
    testOptions { unitTests.isReturnDefaultValues = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].assets.srcDir(rootProject.file("engine/build/dict"))
    sourceSets["main"].assets.srcDir(rootProject.file("engine/build/emoji"))
    // Dictionaries are memory-mapped straight from the APK, so they must stay uncompressed.
    androidResources { noCompress += "dict" }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":engine"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.autofill:autofill:1.3.0") // inline autofill chip styles (Android 11+)
    testImplementation("junit:junit:4.13.2")
}

tasks.named("preBuild") { dependsOn(":engine:buildDicts", ":engine:buildEmoji") }

tasks.withType<Test>().configureEach {
    // The accuracy harness is slow (it rebuilds dictionaries); run it with -Pharness.
    systemProperty("harness", project.hasProperty("harness").toString())
    maxHeapSize = "3g"
}
