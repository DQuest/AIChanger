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
        versionCode = 33
        versionName = "3.11"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            // Тот же ключ, что у debug-сборок (в CI — секрет CHANGERKEY): release-APK ставится поверх прежних
            // debug-сборок без удаления, иначе пропали бы сопряжение и настройки.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Список языков для выбора языка приложения в настройках Android 13+ (values/ — английский, см. resources.properties).
    androidResources {
        generateLocaleConfig = true
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
