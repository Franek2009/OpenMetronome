plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.openmetronome"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "org.openmetronome"
        minSdk = 26
        targetSdk = 37
        versionCode = 12
        versionName = "0.12.0-beta.1"
        testInstrumentationRunner = "org.openmetronome.OpenMetronomeTestRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    val signingValues = listOf("OM_KEYSTORE_PATH", "OM_KEYSTORE_PASSWORD", "OM_KEY_ALIAS", "OM_KEY_PASSWORD")
        .associateWith { providers.environmentVariable(it).orNull }
    val hasSigning = signingValues.values.all { !it.isNullOrBlank() }
    require(hasSigning || signingValues.values.all { it == null }) { "Incomplete release signing environment" }
    require(providers.gradleProperty("requireReleaseSigning").orNull != "true" || hasSigning) {
        "Official releases require all OM_KEYSTORE_* / OM_KEY_* signing variables"
    }
    if (hasSigning) {
        signingConfigs.create("official") {
            storeFile = file(signingValues.getValue("OM_KEYSTORE_PATH")!!)
            storePassword = signingValues.getValue("OM_KEYSTORE_PASSWORD")
            keyAlias = signingValues.getValue("OM_KEY_ALIAS")
            keyPassword = signingValues.getValue("OM_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("official")
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    // Room's migration helper is compiled against 1.8.1. Android test runtime
    // follows the app runtime; keep both aligned instead of downgrading it to 1.7.3.
    implementation(platform(libs.kotlinx.serialization.bom))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.datastore)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation(libs.androidx.room.testing)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

room { schemaDirectory("$projectDir/schemas") }
