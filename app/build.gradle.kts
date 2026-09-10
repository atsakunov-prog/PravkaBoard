import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Номер сборки берём из GitHub Actions (GITHUB_RUN_NUMBER); локально 0.
val ciBuildNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "ru.tsakunov.pravka"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.tsakunov.pravka"
        minSdk = 26
        targetSdk = 36
        // versionCode растёт с каждой сборкой в CI, иначе Android не даст поставить обновление поверх.
        versionCode = if (ciBuildNumber > 0) ciBuildNumber else 1
        versionName = "0.2.0"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("int", "BUILD_NUMBER", ciBuildNumber.toString())
    }

    // Один общий ключ для debug и release, чтобы обновления ставились поверх
    // без удаления приложения (и без потери статистики Бори).
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/pravka.jks")
            storePassword = "pravka-borya-2026"
            keyAlias = "pravka"
            keyPassword = "pravka-borya-2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("shared")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.exifinterface)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    // org.json в юнит-тестах: на Android он в платформе, а в JVM-тестах нужен настоящий.
    testImplementation(libs.org.json)
}
