plugins { id("com.android.application") }
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
        versionCode = 3
        versionName = "0.1.2"
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
    buildTypes { release {
        isMinifyEnabled = false
        if (releaseStore != null) signingConfig = signingConfigs.getByName("assistRelease")
    } }
}
dependencies {
    implementation("net.openid:appauth:0.11.1")
    testImplementation("junit:junit:4.13.2")
}
