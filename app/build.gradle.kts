plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "ke.elgonlabs.barberbeyond"
    compileSdk = 35
    defaultConfig {
        // Stable identity for v1.3 and later signed releases. Never change it in future versions.
        applicationId = "ke.elgonlabs.barberbeyond.manager"
        minSdk = 30
        targetSdk = 35
        versionCode = 10
        versionName = "1.6.2"
    }
    signingConfigs {
        create("barberRelease") {
            // Signing material is supplied only by GitHub Actions secrets. Never commit a keystore.
            val path = System.getenv("BB_RELEASE_KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("BB_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("BB_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("BB_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            // Isolated v1.5.1 preview: preserves v1.4 and v1.5 preview installations and records.
            applicationIdSuffix = ".preview162"
            versionNameSuffix = "-preview"
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("barberRelease")
            isMinifyEnabled = false
        }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.foundation:foundation:1.7.5")
    implementation("androidx.compose.runtime:runtime:1.7.5")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
