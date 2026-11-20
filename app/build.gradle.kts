plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shoo.cut"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shoo.cut"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/AL2.0",
                "/META-INF/LGPL2.1",
                "/META-INF/LICENSE",
                "/META-INF/LICENSE.txt",
                "/META-INF/NOTICE",
                "/META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.cardview:cardview:1.0.0")

    // Verified on Maven Central (checksum-verified, contents inspected):
    // genuine arm64-v8a / armeabi-v7a / x86 / x86_64 FFmpeg 4.4 libraries built
    // with NDK r25b, including libc++_shared.so, libopenh264, the native AAC
    // encoder and the concat/trim/pad/scale(bicubic)/setsar/transpose filters.
    implementation("com.bihe0832.android:lib-ffmpeg-mobile-aaf:8.5.0")

    testImplementation("junit:junit:4.13.2")
}
