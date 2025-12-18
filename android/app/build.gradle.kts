plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.firebase.appdistribution)
}

android {
    namespace = "com.voicelink.connect"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voicelink.connect"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ABI splits to reduce APK size (WebRTC includes native libs for all architectures)
    splits {
        abi {
            isEnable = true
            reset()
            // Most Android devices use arm64-v8a, some older use armeabi-v7a
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false // Don't create a universal APK
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // Firebase App Distribution for release builds
            firebaseAppDistribution {
                artifactType = "APK"
                // Add your tester emails here (comma-separated)
                testers = "fariha.fhf@gmail.com, khadiza.perveen1456@gmail.com, yusharayan67@gmail.com, 20hozaifa02@gmail.com"
                // Or use a file: testersFile = "testers.txt"
            }
        }
        debug {
            isMinifyEnabled = false
            versionNameSuffix = "-debug"
            
            // Firebase App Distribution for debug builds
            firebaseAppDistribution {
                artifactType = "APK"
                testers = "fariha.fhf@gmail.com, khadiza.perveen1456@gmail.com, yusharayan67@gmail.com, 20hozaifa02@gmail.com"
            }
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // WebRTC
    implementation(libs.webrtc)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)

    // Accompanist
    implementation(libs.accompanist.permissions)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
