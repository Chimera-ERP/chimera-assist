plugins { id("com.android.application") }
android {
    namespace = "ro.chimeraerp.assist"
    compileSdk = 36
    defaultConfig {
        applicationId = "ro.chimeraerp.assist"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["appAuthRedirectScheme"] = "ro.chimeraerp.assist"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation("net.openid:appauth:0.11.1")
    testImplementation("junit:junit:4.13.2")
}
