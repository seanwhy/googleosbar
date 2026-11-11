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
                keyAlias = "bentobar" // the Meridian11 release key, made on 2026-11-03 for 1.7 (the earlier keys were retired)
                keyPassword = pw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // A debug build also offers the pseudolocales en-XA and ar-XB, which show text that was built in code and a layout that doesn't mirror.
        debug { isPseudoLocalesEnabled = true }
        // `-PreleaseSignedDebug`: a debug build signed with the release key, so it installs over a released BentoBar on
        // a test device and keeps its layout and its accessibility switch (same id, same key, same version code).
        if (project.hasProperty("releaseSignedDebug")) debug { signingConfig = signingConfigs.getByName("release") }
        // Only for the Compose tests in src/testComposeTest (`:app:testComposeTestUnitTest`): a build type gives them a source
        // set and dependencies of their own, so Robolectric and the test activity's manifest stay out of the fast tests and the APKs.
        create("composeTest") { initWith(getByName("debug")) }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Lint is a gate (.github/workflows/ci.yml): any new error fails the build, and the known ones are in
        // lint-baseline.xml (each with its reason) or suppressed in code with a reason. Warnings stay warnings.
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
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
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.savedstate)
    implementation(libs.kotlinx.serialization.json)
    // Used directly (flows, launch); declared so a library dropping it cannot break the build.
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    testImplementation(libs.junit)
    // The Compose tests alone (see the build type): Robolectric hosts the composables on the JVM, ui-test drives them, and
    // ui-test-manifest declares the empty activity they are set into.
    add("testComposeTestImplementation", platform(libs.androidx.compose.bom))
    add("testComposeTestImplementation", libs.androidx.compose.ui.test.junit4)
    add("testComposeTestImplementation", libs.robolectric)
    // ui-test brings 3.5, which calls a method Android 17 no longer has.
    add("testComposeTestImplementation", libs.androidx.test.espresso.core)
    add("composeTestImplementation", libs.androidx.compose.ui.test.manifest)
}

androidComponents {
    // Host tests are on for the tested build type alone, unless asked for. Robolectric reads the merged resources, and only
    // this variant's tests get them: the fast tests stay resource-free.
    beforeVariants(selector().withBuildType("composeTest")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.let {
            it.enable = true
            it.includeAndroidResources = true
        }
    }
    // src/test belongs to every variant's tests, so this variant compiles it too. Only the Compose tests run here.
    onVariants(selector().withBuildType("composeTest")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.configureTestTask {
            it.filter.includeTestsMatching("com.meridian11.books.bentobar.compose.*")
            // Robolectric's Android 17 reaches this JDK-internal class, which isn't exported by default.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

// Tests read src/main/res and the manifest by path (ManifestTest, LocaleCopiesTest and others). Gradle does not know
// that, so without these inputs an edit to the manifest or a string leaves testDebugUnitTest UP-TO-DATE or
// FROM-CACHE and the tests that pin them never run.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/res").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("mainRes")
    inputs.file("src/main/AndroidManifest.xml").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("mainManifest")
    // A failure shows its test, message and stack in the log itself: on a CI runner there is only the log.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}

val copyDebugApkToDownload by tasks.registering(Copy::class) {
    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
    }
    into(rootProject.projectDir.resolve("../Download"))
    rename("app-debug.apk", "BentoBar-debug.apk")
}

val copyReleaseApkToDownload by tasks.registering(Copy::class) {
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
    }
    into(rootProject.projectDir.resolve("../Download"))
    rename { "BentoBar-release.apk" }
}

tasks.matching { it.name == "assembleDebug" || it.name == "packageDebug" }.configureEach {
    finalizedBy(copyDebugApkToDownload)
}

tasks.matching { it.name == "assembleRelease" || it.name == "packageRelease" }.configureEach {
    finalizedBy(copyReleaseApkToDownload)
}
