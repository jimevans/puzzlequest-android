import java.net.URI

// What scripts/start-test-server.sh writes to .test-server/env.
val testServerSettings =
    listOf(
        "PQ_TEST_SERVER_URL",
        "PQ_TEST_USER_NAME",
        "PQ_TEST_PASSWORD",
        "PQ_TEST_DISPLAY_NAME",
        "PQ_TEST_ADMIN_USER_NAME",
        "PQ_TEST_ADMIN_PASSWORD",
    )

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Screens' routes are serializable classes (Navigation Compose's type-safe routes).
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.thepuzzlequest.puzzlequest"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.thepuzzlequest.puzzlequest"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // The site a release build uses; debug builds use a server on the
        // Mac (below).
        buildConfigField("String", "SERVER_URL", "\"https://thepuzzlequest.com\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The UI tests use the test server (scripts/start-test-server.sh),
        // named by the variables in .test-server/env when they're in the
        // environment.
        for (name in testServerSettings) {
            providers.environmentVariable(name).orNull?.let { testInstrumentationRunnerArguments[name] = it }
        }
    }

    buildTypes {
        debug {
            // A server run on this Mac (npm run dev in the server repository),
            // reached through `adb reverse tcp:3000 tcp:3000`, which forwards
            // the phone's port 3000 to the Mac's. (Android 17 keeps apps
            // that target it off the local network, which the emulator's
            // 10.0.2.2 alias for the Mac is on; the phone's own localhost is
            // allowed.)
            buildConfigField("String", "SERVER_URL", "\"http://localhost:3000\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // Any warning fails the build; app/lint.xml lists what's set aside, and why.
        warningsAsErrors = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Before the UI tests, the test server's port on the phone is forwarded to
// the Mac (`adb reverse`), so the app reaches it at localhost, as the
// variables name it. With more than one phone or emulator connected, set
// ANDROID_SERIAL to the one to use.
val testServerUrl = providers.environmentVariable("PQ_TEST_SERVER_URL")
val forwardTestServer =
    tasks.register<Exec>("forwardTestServer") {
        description = "Forwards the test server's port on the phone to this computer."
        val adb = androidComponents.sdkComponents.adb
        onlyIf { testServerUrl.isPresent }
        doFirst {
            val port = URI(testServerUrl.get()).port
            commandLine(adb.get().asFile.path, "reverse", "tcp:$port", "tcp:$port")
        }
    }
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach { dependsOn(forwardTestServer) }
