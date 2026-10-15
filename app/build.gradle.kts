import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.aboutlibraries)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun config(name: String): String =
    (project.findProperty(name) as String?)
        ?: localProps.getProperty(name)
        ?: System.getenv(name.uppercase().replace('.', '_'))
        ?: ""

val allowDebugSignedRelease = config("finalish.allowDebugSignedRelease") == "true"
val imsClientId = config("finalish.imsClientId")
// Where in-app reports go (relay/, a Cloudflare Worker that opens the GitHub issues). Not a secret,
// but set per build (CI variable, cloud environment or local.properties): empty, reports go out
// through the share sheet instead.
val reportRelay = config("finalish.reportRelay")
val redirectUri = config("finalish.redirectUri")
// Android needs the custom scheme at build time to register the OAuth callback.
val redirectScheme = redirectUri.substringBefore("://", "").ifBlank { "finalish-auth" }

android {
    // Lists the translated languages for Android 13+'s per-app language settings (locales_config).
    androidResources { generateLocaleConfig = true }
    // The in-app language picker needs every language in the base APK, not split out per device.
    bundle { language { enableSplit = false } }
    namespace = "dev.finalish"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.finalish"
        minSdk = 26
        targetSdk = 37
        // Each CI run gets a higher code (Play refuses a repeated one); local builds stay at 1.
        // finalish.versionCode overrides both. On a v* tag the name comes from the tag.
        versionCode = config("finalish.versionCode").ifBlank { System.getenv("GITHUB_RUN_NUMBER").orEmpty() }.toIntOrNull() ?: 1
        versionName = System.getenv("GITHUB_REF_NAME")?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" && it.startsWith("v") }?.removePrefix("v") ?: "0.1.0"

        buildConfigField("String", "IMS_CLIENT_ID", "\"$imsClientId\"")
        buildConfigField("String", "IMS_REDIRECT_URI", "\"$redirectUri\"")
        buildConfigField("String", "REDIRECT_SCHEME", "\"$redirectScheme\"")
        buildConfigField("String", "REPORT_RELAY_URL", "\"$reportRelay\"")
        manifestPlaceholders["redirectScheme"] = redirectScheme
        manifestPlaceholders["appLabel"] = "@string/app_name"

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // One debug key for every machine and cloud session, so a new debug build installs over the
        // one on the test phones without wiping their data. Not in git: the cloud session hook writes
        // it from DEBUG_KEYSTORE_B64. Without the file, AGP's own debug key is used.
        val sharedDebugKey = file("debug.keystore")
        if (sharedDebugKey.exists()) getByName("debug") {
            storeFile = sharedDebugKey
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val storePath = config("finalish.keystore")
            if (storePath.isNotBlank()) {
                storeFile = rootProject.file(storePath)
                storePassword = config("finalish.keystorePassword")
                keyAlias = config("finalish.keyAlias")
                keyPassword = config("finalish.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No release key: refuse to package (see below) unless debug signing is asked for
            // explicitly (-Pfinalish.allowDebugSignedRelease=true), for a local test of the minified build.
            // A release signed with the public debug key could be "updated" by anyone holding that key.
            val release = signingConfigs.getByName("release")
            signingConfig = when {
                release.storeFile != null -> release
                allowDebugSignedRelease -> signingConfigs.getByName("debug")
                else -> null
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            // Tell the two installs apart on the launcher.
            manifestPlaceholders["appLabel"] = "Final(ish) Debug"
            versionNameSuffix = "-debug"
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
    testOptions {
        // Screenshot tests (Roborazzi on Robolectric) render Compose on the JVM, no device needed.
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    // Lint is at zero: any new warning fails the build instead of piling up unnoticed.
    lint {
        abortOnError = true
        warningsAsErrors = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.animation.ExperimentalSharedTransitionApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

composeCompiler {
    // Strong skipping keeps recompositions cheap in long lists and the player overlay.
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.zxing.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.splashscreen)
    // Installs the bundled Baseline Profile on devices that sideload the APK.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.aboutlibraries.compose.m3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.video)
    implementation(libs.coil.svg)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.database)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

// A release without the release key fails instead of quietly coming out unsigned or debug-signed.
tasks.matching { it.name == "packageRelease" || it.name == "signReleaseBundle" }.configureEach {
    doFirst {
        if (config("finalish.keystore").isBlank() && !allowDebugSignedRelease) {
            throw GradleException(
                "No release signing key: set finalish.keystore (and its passwords), or pass " +
                    "-Pfinalish.allowDebugSignedRelease=true for a local test build.",
            )
        }
    }
}
