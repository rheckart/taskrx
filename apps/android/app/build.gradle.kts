plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.taskrx.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.taskrx.mobile"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and resources on the classpath.
            isIncludeAndroidResources = true
            all {
                // The same zone :core's suite pins, so day-bucketed reads mean the
                // same thing under test as they do in the shared rules' tests.
                it.jvmArgs("-Duser.timezone=UTC")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test-junit"))
}

ksp {
    // A fresh version-1 schema with no migrations to verify (spec phase 3), so
    // there is no exported schema to keep in step.
    arg("room.generateKotlin", "true")
}
