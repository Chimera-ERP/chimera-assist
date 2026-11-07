plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("dev.flutter.flutter-gradle-plugin")
}
val releaseStore = System.getenv("ASSIST_KEYSTORE_PATH")
val releaseCredentials = listOf("ASSIST_KEYSTORE_PASSWORD", "ASSIST_KEY_ALIAS", "ASSIST_KEY_PASSWORD")
if (releaseStore != null) require(releaseCredentials.all { !System.getenv(it).isNullOrBlank() }) { "Incomplete Assist signing configuration" }
if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
    require(!releaseStore.isNullOrBlank()) { "Release builds require the persistent Assist signing identity" }
}
android {
    namespace = "ro.chimeraerp.assist"
    compileSdk = 36
    defaultConfig {
        applicationId = "ro.chimeraerp.assist"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.3.0"
        manifestPlaceholders["appAuthRedirectScheme"] = "ro.chimeraerp.assist"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (releaseStore != null) create("assistRelease") {
            storeFile = file(releaseStore)
            storePassword = System.getenv("ASSIST_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ASSIST_KEY_ALIAS")
            keyPassword = System.getenv("ASSIST_KEY_PASSWORD")
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildTypes { release {
        isMinifyEnabled = false
        isShrinkResources = false
        if (releaseStore != null) signingConfig = signingConfigs.getByName("assistRelease")
    } }
}
dependencies {
    implementation("net.openid:appauth:0.11.1")
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.43")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
flutter { source = "../.." }

// AGP's resource-enabled unit test package consumes the Flutter assets directory.
tasks.matching { it.name == "packageDebugUnitTestForUnitTest" }.configureEach {
    dependsOn("copyFlutterAssetsDebug")
}
