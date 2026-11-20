plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.rustvnt.vntapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rustvnt.vntapp"
        minSdk = 24
        targetSdk = 36
        versionCode = providers.gradleProperty("vntVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("vntVersionName").orNull ?: "1.0"
        resValue("string", "vnt_core_tag", providers.gradleProperty("vntCoreTag").orNull ?: "unknown")

        providers.gradleProperty("vntAbi").orNull?.let { requestedAbi ->
            ndk.abiFilters += requestedAbi
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        resValues = true
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }

    lint {
        abortOnError = false
    }

    testOptions {
        // 让 Build.MODEL 等 android.jar 桩方法返回默认值，便于纯 JVM 单测覆盖配置序列化。
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.drawerlayout)
    implementation(libs.zxing.android.embedded)
    testImplementation(libs.junit)
    // android.jar 中的 org.json 只是桩，纯 JVM 单测需要真实实现。
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
