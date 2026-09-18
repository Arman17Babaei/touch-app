import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val releaseSigningStoreFile = providers.environmentVariable("TOUCH_SIGNING_STORE_FILE").orNull
val releaseSigningStorePassword = providers.environmentVariable("TOUCH_SIGNING_STORE_PASSWORD").orNull
val releaseSigningKeyAlias = providers.environmentVariable("TOUCH_SIGNING_KEY_ALIAS").orNull
val releaseSigningKeyPassword = providers.environmentVariable("TOUCH_SIGNING_KEY_PASSWORD").orNull
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
fun firebaseProperty(name: String): String = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .orElse(localProperties.getProperty(name, ""))
    .get()
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
        applicationId = "ir.armanbabaei.touch"
        minSdk = 30
        targetSdk = 35
        versionCode = providers.gradleProperty("TOUCH_VERSION_CODE").map { it.toInt() * 10 + 2 }.orElse(2).get()
        versionName = providers.gradleProperty("TOUCH_VERSION_NAME").orElse("0.1.0").get()

        resValue("string", "google_app_id", firebaseProperty("TOUCH_FIREBASE_APP_ID"))
        resValue("string", "gcm_defaultSenderId", firebaseProperty("TOUCH_FIREBASE_SENDER_ID"))
        resValue("string", "google_api_key", firebaseProperty("TOUCH_FIREBASE_API_KEY"))
        resValue("string", "project_id", firebaseProperty("TOUCH_FIREBASE_PROJECT_ID"))
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
