plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.elarbi.omniiptv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.elarbi.omniiptv"
        minSdk = 26
        targetSdk = 34
        versionCode = 20
        versionName = "1.3.0"
        // Téléviseurs Google TV / Android TV et téléphones : processeurs ARM (garde l'APK léger malgré FFmpeg).
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    // Clé de signature FIXE : chaque nouvelle version s'installe par-dessus l'ancienne
    // (portails, listes de chaînes, favoris et réglages conservés).
    signingConfigs {
        create("omni") {
            storeFile = file("omni-release.jks")
            storePassword = "omniiptv2026"
            keyAlias = "omni"
            keyPassword = "omniiptv2026"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("omni")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-datasource-rtmp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Décodeur audio FFmpeg (AC3, E-AC3, DTS, MP2, TrueHD…) pour les chaînes dont le son n'est pas lu par le téléviseur.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
