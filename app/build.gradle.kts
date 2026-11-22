plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.reimusicplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.reimusicplayer"
        minSdk = 23
        targetSdk = 35
        versionCode = 14
        versionName = "0.6.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Reprodução e integração de biblioteca com Android Auto.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("com.google.guava:guava:33.3.1-android")
}
