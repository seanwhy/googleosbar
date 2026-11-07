plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.meridian11.books.bentobar"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.meridian11.books.bentobar"
        minSdk = 34
        targetSdk = 37
        versionCode = 23
        versionName = "1.7.3"
    }

    // Release signing from ~/.config/bentobar (never committed). Absent -> unsigned release build.
    val keyDir = File(System.getProperty("user.home"), ".config/bentobar")
    val keyFile = File(keyDir, "keystore.jks")
    val keyPassFile = File(keyDir, "keystore.pass")
    signingConfigs {
        if (keyFile.exists() && keyPassFile.exists()) {
            create("release") {
                storeFile = keyFile
                val pw = keyPassFile.readText().trim()
                storePassword = pw
                keyAlias = "bentobar" // the release key made on 2026-09-30 (the DiscoBar-era key was retired)
                keyPassword = pw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // `-PreleaseSignedDebug`: a debug build signed with the release key, so it installs over a released BentoBar on
        // a test device and keeps its layout and its accessibility switch (same id, same key, same version code).
        if (project.hasProperty("releaseSignedDebug")) debug { signingConfig = signingConfigs.getByName("release") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.savedstate)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}

val copyDebugApkToDownload by tasks.registering(Copy::class) {
    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
    }
    into(rootProject.projectDir.resolve("../Download"))
    rename("app-debug.apk", "BentoBar-debug.apk")
}

tasks.matching { it.name == "assembleDebug" || it.name == "packageDebug" }.configureEach {
    finalizedBy(copyDebugApkToDownload)
}
