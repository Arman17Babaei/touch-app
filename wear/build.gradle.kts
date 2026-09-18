plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val releaseSigningStoreFile = providers.environmentVariable("TOUCH_SIGNING_STORE_FILE").orNull
val releaseSigningStorePassword = providers.environmentVariable("TOUCH_SIGNING_STORE_PASSWORD").orNull
val releaseSigningKeyAlias = providers.environmentVariable("TOUCH_SIGNING_KEY_ALIAS").orNull
val releaseSigningKeyPassword = providers.environmentVariable("TOUCH_SIGNING_KEY_PASSWORD").orNull
val releaseSigningEnabled = listOf(
    releaseSigningStoreFile,
    releaseSigningStorePassword,
    releaseSigningKeyAlias,
    releaseSigningKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "app.touch.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.touch.wear"
        minSdk = 30
        targetSdk = 35
        versionCode = providers.gradleProperty("TOUCH_VERSION_CODE").map(String::toInt).orElse(1).get()
        versionName = providers.gradleProperty("TOUCH_VERSION_NAME").orElse("0.1.0").get()

        resValue("string", "google_app_id", providers.gradleProperty("TOUCH_WEAR_FIREBASE_APP_ID").orElse(providers.environmentVariable("TOUCH_WEAR_FIREBASE_APP_ID")).orElse("").get())
        resValue("string", "gcm_defaultSenderId", providers.gradleProperty("TOUCH_FIREBASE_SENDER_ID").orElse(providers.environmentVariable("TOUCH_FIREBASE_SENDER_ID")).orElse("").get())
        resValue("string", "google_api_key", providers.gradleProperty("TOUCH_FIREBASE_API_KEY").orElse(providers.environmentVariable("TOUCH_FIREBASE_API_KEY")).orElse("").get())
        resValue("string", "project_id", providers.gradleProperty("TOUCH_FIREBASE_PROJECT_ID").orElse(providers.environmentVariable("TOUCH_FIREBASE_PROJECT_ID")).orElse("").get())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    signingConfigs {
        if (releaseSigningEnabled) {
            create("release") {
                storeFile = file(releaseSigningStoreFile!!)
                storePassword = releaseSigningStorePassword
                keyAlias = releaseSigningKeyAlias
                keyPassword = releaseSigningKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":communication"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
