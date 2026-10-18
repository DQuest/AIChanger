plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.dquest.aichanger"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.dquest.aichanger"
        minSdk = 30
        targetSdk = 36
        versionCode = 30
        versionName = "3.8"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            // Если включить R8, правила уже на месте: демон запускается по имени класса через app_process.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.libadb.android)
    implementation(libs.conscrypt.android)
    implementation(libs.bouncycastle.pkix)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.junit)
}
