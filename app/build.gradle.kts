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
        versionCode = 18
        versionName = "1.0.0"
        testInstrumentationRunner = "org.openmetronome.OpenMetronomeTestRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Each distribution owns its signing credentials. Never fall back from DEV to prod.
    fun configureSigning(name: String, prefix: String, requiredProperty: String): Boolean {
        val values = listOf("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
            .associateWith { providers.environmentVariable("${prefix}_$it").orNull }
        val complete = values.values.all { !it.isNullOrBlank() }
        require(complete || values.values.all { it == null }) { "Incomplete $name signing environment" }
        require(providers.gradleProperty(requiredProperty).orNull != "true" || complete) {
            "$name signing requires all ${prefix}_KEYSTORE_* / ${prefix}_KEY_* variables"
        }
        if (complete) signingConfigs.create(name) {
            storeFile = file(values.getValue("KEYSTORE_PATH")!!)
            storePassword = values.getValue("KEYSTORE_PASSWORD")
            keyAlias = values.getValue("KEY_ALIAS")
            keyPassword = values.getValue("KEY_PASSWORD")
        }
        return complete
    }

    val hasOfficialSigning = configureSigning("official", "OM", "requireReleaseSigning")
    val hasDevSigning = configureSigning("development", "OM_DEV", "requireDevSigning")
    flavorDimensions += "distribution"
    productFlavors {
        create("prod") {
            dimension = "distribution"
            if (hasOfficialSigning) signingConfig = signingConfigs.getByName("official")
        }
        create("dev") {
            dimension = "distribution"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            if (hasDevSigning) signingConfig = signingConfigs.getByName("development")
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
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
