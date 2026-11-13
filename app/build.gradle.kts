import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Signing material is never committed. Locally it comes from keystore.properties
// (git-ignored); in CI it comes from repository secrets exported as env vars.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun signingValue(property: String, env: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(env)

android {
    namespace = "dev.wetube.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.wetube.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.1.7"
    }

    // Keep the APK free of the dependency metadata block; nothing in the app
    // reads it and it only adds bytes to distribute.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "WETUBE_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank()) {
                val store = rootProject.file(storePath).takeIf { it.exists() } ?: file(storePath)
                if (store.exists()) {
                    storeFile = store
                    storePassword = signingValue("storePassword", "WETUBE_KEYSTORE_PASSWORD")
                    keyAlias = signingValue("keyAlias", "WETUBE_KEY_ALIAS")
                    keyPassword = signingValue("keyPassword", "WETUBE_KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            // Signing is never silently downgraded. With no release key the
            // release build fails, so a debug-signed artifact can never be
            // mistaken for a real one. `-PallowDebugSigning=true` is the
            // explicit opt-in for a local test build on a key-less checkout.
            //
            // The failure is raised when the release is packaged, not while
            // this block is configured, so `assembleDebug` and the unit tests
            // keep working without signing material.
            val releaseSigning = signingConfigs.getByName("release")
            val debugSigningAllowed = providers.gradleProperty("allowDebugSigning")
                .map { it.equals("true", ignoreCase = true) }
                .getOrElse(false)

            when {
                releaseSigning.storeFile != null -> signingConfig = releaseSigning
                debugSigningAllowed -> signingConfig = signingConfigs.getByName("debug")
                else -> tasks
                    .matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }
                    .configureEach {
                        doFirst {
                            throw GradleException(
                                "No release signing key was found. Add keystore.properties " +
                                    "(storeFile, storePassword, keyAlias, keyPassword) or set the " +
                                    "WETUBE_KEYSTORE_* environment variables. To sign a local test " +
                                    "release with the debug key instead, pass -PallowDebugSigning=true.",
                            )
                        }
                    }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkDependencies = true
        // Version bumps are handled deliberately, not on every CI run.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
