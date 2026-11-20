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
        jniLibs {
            // The FFmpeg binary in the pao11 AAR is executed as a process, so the
            // native library must be extracted (and executable) at install time.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Verified on Maven Central: ships libffmpeg.so for arm64-v8a and
    // armeabi-v7a, built with libx264, the native AAC encoder, bicubic scale,
    // concat/pad/trim filters. No ffprobe binary is included.
    implementation("io.github.pao11:ffmpeg-android:1.1.0")

    testImplementation("junit:junit:4.13.2")
}
