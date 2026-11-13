plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.vision"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.vision"
        minSdk = 28
        targetSdk = 37
        versionCode = 6
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Optional deployment build for the connected phone; default remains a universal APK.
        providers.gradleProperty("visionAbi").orNull?.let { selectedAbi -> ndk { abiFilters += selectedAbi } }
    }

    // Signing material is provided by the publisher's environment, never by committed files.
    val releaseStore = providers.environmentVariable("VISION_STORE_FILE").orNull
    signingConfigs {
        if (releaseStore != null) {
            create("publication") {
                storeFile = file(releaseStore)
                storePassword = providers.environmentVariable("VISION_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("VISION_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("VISION_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            signingConfig = signingConfigs.findByName("publication")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.ai.edge.litert:litert:2.1.6")
    implementation("org.opencv:opencv:5.0.0.1")
    implementation("com.google.ar:core:1.56.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    // Keep ARCore's transitive Fragment dependency compatible with Activity Result APIs.
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
