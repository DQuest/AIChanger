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
        versionCode = 10
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libadb.android)
    implementation(libs.conscrypt.android)
    implementation(libs.bouncycastle.pkix)
}
