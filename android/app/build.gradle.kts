plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.firebase.appdistribution)
}

android {
    namespace = "com.streamsync.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.streamsync.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Ship only the ABIs we actually support — drops ~half of native binaries from the APK.
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }

        // Single-locale builds — drops translated string tables from libraries we depend on.
        resourceConfigurations.addAll(listOf("en"))

        // Vector drawables only; no PNG generation at build time.
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.version",
                "**/kotlin-tooling-metadata.json",
                "kotlin/**"
            )
        }
    }

    // ABI splits disabled - Firebase App Distribution doesn't support multiple APKs
    // APK size will be larger (~100MB) but distribution works
    // For Play Store release, you can enable splits and upload via Play Console instead
    // splits {
    //     abi {
    //         isEnable = true
    //         reset()
    //         include("armeabi-v7a", "arm64-v8a")
    //         isUniversalApk = false
    //     }
    // }

    signingConfigs {
        create("release") {
            // CI provides these via secrets; locally, set them in ~/.gradle/gradle.properties
            // or as environment variables (KEYSTORE_PATH, KEY_ALIAS, KEY_PASSWORD, STORE_PASSWORD).
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: (project.findProperty("KEYSTORE_PATH") as String?)
            val alias = System.getenv("KEY_ALIAS")
                ?: (project.findProperty("KEY_ALIAS") as String?)
            val keyPwd = System.getenv("KEY_PASSWORD")
                ?: (project.findProperty("KEY_PASSWORD") as String?)
            val storePwd = System.getenv("STORE_PASSWORD")
                ?: (project.findProperty("STORE_PASSWORD") as String?)

            if (!keystorePath.isNullOrBlank() && !alias.isNullOrBlank()
                && !keyPwd.isNullOrBlank() && !storePwd.isNullOrBlank()
            ) {
                storeFile = file(keystorePath)
                keyAlias = alias
                keyPassword = keyPwd
                storePassword = storePwd
            }
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

            // Apply release signing only when credentials are available;
            // otherwise the build falls back to debug signing for local convenience.
            signingConfigs.getByName("release").storeFile?.let {
                signingConfig = signingConfigs.getByName("release")
            }
            
            // Firebase App Distribution for release builds
            firebaseAppDistribution {
                artifactType = "APK"
                // Add your tester emails here (comma-separated)
                testers = "fariha.fhf@gmail.com, khadiza.perveen1456@gmail.com, yusharayan67@gmail.com, 20hozaifa02@gmail.com, aylinjahananj@gmail.com, smazmainh@gmail.com, khairunnsara@gmail.com"
                // Or use a file: testersFile = "testers.txt"
            }
        }
        debug {
            isMinifyEnabled = false
            versionNameSuffix = "-debug"
            
            // Firebase App Distribution for debug builds
            firebaseAppDistribution {
                artifactType = "APK"
                testers = "fariha.fhf@gmail.com, khadiza.perveen1456@gmail.com, yusharayan67@gmail.com, 20hozaifa02@gmail.com, aylinjahananj@gmail.com, smazmainh@gmail.com, khairunnsara@gmail.com"
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
    
    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
