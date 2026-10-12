import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// CONVEX_URL and CLERK_PUBLISHABLE_KEY come from local.properties (gitignored), or the
// environment for CI. Empty means "not configured": the app then runs local-only.
val localProperties =
    Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
    }

fun setting(key: String): String = localProperties.getProperty(key) ?: System.getenv(key) ?: ""

android {
    namespace = "com.taskrx.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.taskrx.mobile"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "CONVEX_URL", "\"${setting("CONVEX_URL")}\"")
        buildConfigField("String", "CLERK_PUBLISHABLE_KEY", "\"${setting("CLERK_PUBLISHABLE_KEY")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.convex.mobile)
    implementation(libs.clerk.android.api)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
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
