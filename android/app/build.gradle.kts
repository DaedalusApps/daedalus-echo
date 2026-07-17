import java.util.Properties
import java.io.File
import java.io.FileInputStream
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

val versionPropsFile = file("../version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        load(FileInputStream(versionPropsFile))
    }
}

// Get version code from git commit count, fallback to property file
val gitCommitCount = try {
    Runtime.getRuntime().exec("git rev-list --count HEAD").inputStream.bufferedReader().readText().trim().toInt()
} catch (e: Exception) {
    versionProps.getProperty("BUILD_NUMBER", "1").toInt()
}

val vName = versionProps.getProperty("VERSION_NAME", "1.0.0")

// sherpa-onnx ships only a prebuilt Android .aar (no Maven artifact), so we fetch it
// at build time instead of committing the 56 MB binary. See downloadSherpaOnnx below.
val sherpaOnnxVersion = "1.13.2"

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile))
}

android {
    namespace   = "com.daedalusapps.echo"
    compileSdk  = 35

    defaultConfig {
        applicationId   = "com.daedalusapps.echo"
        minSdk          = 26
        targetSdk       = 35
        versionCode     = gitCommitCount
        versionName     = vName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile   = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias    = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePropsFile.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // Expose the exported Room schema JSONs to Robolectric MigrationTestHelper.
    // Robolectric reads the merged debug assets (not the test sourceSet), so the
    // schemas are attached to the debug variant — they stay out of the release APK.
    sourceSets.getByName("debug") {
        assets.srcDir(files("$projectDir/schemas"))
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.ext)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.navigation.compose)
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.coroutines.android)

    // Room (KSP instead of kapt — faster, no daemon issues)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // HTTP (model download only)
    implementation(libs.okhttp)

    // On-device AI — MediaPipe LLM Inference (tasks-genai .bin format models)
    implementation(libs.mediapipe.genai)
    // On-device text embeddings for semantic note search
    implementation(libs.mediapipe.tasks.text)

    // Audio playback
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // Whisper STT via sherpa-onnx (downloaded by the downloadSherpaOnnx task)
    implementation(files("libs/sherpa-onnx-$sherpaOnnxVersion.aar"))

    implementation(libs.documentfile)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation("org.json:json:20240303")

    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.mockk.android)
}

// Fetch the prebuilt sherpa-onnx .aar from GitHub releases instead of committing it.
val downloadSherpaOnnx by tasks.registering {
    description = "Downloads the prebuilt sherpa-onnx Android .aar (not published to Maven)."
    val aar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar").asFile
    outputs.file(aar)
    doLast {
        if (aar.exists()) return@doLast
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
        logger.lifecycle("Downloading sherpa-onnx $sherpaOnnxVersion .aar…")
        aar.parentFile.mkdirs()
        val tmp = File.createTempFile("sherpa-onnx", ".aar.part", aar.parentFile)
        URI(url).toURL().openStream().use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        if (!tmp.renameTo(aar)) {
            tmp.copyTo(aar, overwrite = true); tmp.delete()
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadSherpaOnnx) }

// Reinstall the app after instrumented tests (test runner uninstalls it as cleanup)
tasks.whenTaskAdded {
    if (name == "connectedDebugAndroidTest") {
        finalizedBy("installDebug")
    }
}
