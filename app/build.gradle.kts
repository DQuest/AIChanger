plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.dquest.aichanger"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.dquest.aichanger"
        minSdk = 30
        targetSdk = 36
        versionCode = 20
        versionName = "2.8"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.libadb.android)
    implementation(libs.conscrypt.android)
    implementation(libs.bouncycastle.pkix)
}
