import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// ---------------------------------------------------------------------------
// Release signing (PKCS12).
//  * CI: environment variables ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD,
//    ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD (decoded from GitHub Secrets).
//  * Local: a properties file OUTSIDE the project, by default
//    ../_keys/MealSip/signing.properties (override with ANDROID_SIGNING_PROPERTIES).
// Missing credentials fail every release packaging/signing task.
// There is deliberately NO fallback to the debug key.
// ---------------------------------------------------------------------------
val signingPropsFile = (System.getenv("ANDROID_SIGNING_PROPERTIES")?.takeIf { it.isNotBlank() }
    ?.let { file(it) }) ?: rootProject.file("../_keys/MealSip/signing.properties")

val signingProps = Properties().apply {
    if (signingPropsFile.exists()) signingPropsFile.inputStream().use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: signingProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val releaseStorePath: String? = signingValue("ANDROID_KEYSTORE_FILE", "storeFile")
val releaseStoreFile: File? = releaseStorePath?.let { path ->
    val direct = File(path)
    if (direct.isAbsolute) direct else File(signingPropsFile.parentFile ?: rootDir, path)
}
val releaseStorePassword: String? = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias: String? = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword: String? = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")

val hasReleaseSigning = releaseStoreFile?.exists() == true &&
    listOf(releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

// Step 1: verify a signed NON-minified release. R8 + resource shrinking only with -Pmealsip.minify=true.
val minifyRelease = (project.findProperty("mealsip.minify") as String?)?.toBoolean() ?: false

android {
    namespace = "com.egrmeister.mealsip"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.egrmeister.mealsip"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeType = "pkcs12"
            if (hasReleaseSigning) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = minifyRelease
            isShrinkResources = minifyRelease
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        // Versions are pinned on purpose; "newer version available" checks are noise here.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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

// Fail release packaging/signing when credentials are missing — never sign with the debug key.
val releaseSigningTasks = setOf(
    "assembleRelease", "bundleRelease", "packageRelease", "packageReleaseBundle",
    "signReleaseBundle", "validateSigningRelease", "installRelease",
)
gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any { it.project == project && it.name in releaseSigningTasks }
    if (wantsRelease && !hasReleaseSigning) {
        throw GradleException(
            "MealSip release signing credentials are missing. Provide ANDROID_KEYSTORE_FILE, " +
                "ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD, or " +
                "../_keys/MealSip/signing.properties. Release builds never fall back to the debug key."
        )
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigation.common)
    implementation(libs.androidx.navigation.runtime)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.compose.ui.unit)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.runtime.saveable)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.common)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore.preferences.core)
    implementation(libs.androidx.datastore.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
