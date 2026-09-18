plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.touch.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.touch.wear"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        resValue("string", "google_app_id", providers.gradleProperty("TOUCH_WEAR_FIREBASE_APP_ID").orElse("").get())
        resValue("string", "gcm_defaultSenderId", providers.gradleProperty("TOUCH_FIREBASE_SENDER_ID").orElse("").get())
        resValue("string", "google_api_key", providers.gradleProperty("TOUCH_FIREBASE_API_KEY").orElse("").get())
        resValue("string", "project_id", providers.gradleProperty("TOUCH_FIREBASE_PROJECT_ID").orElse("").get())
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
