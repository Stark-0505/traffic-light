import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.DuplicateRule
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutLibraries)
}

kotlin {
    compilerOptions {
        // Kept for @StringRes/@DrawableRes-annotated primary-constructor properties in
        // DataType.kt and HistoryGrouping.kt (e.g. `@StringRes val nameRes: Int` on an enum
        // constructor). Kotlin 2.x changed the default use-site target Kotlin picks for an
        // annotation applied directly to a constructor val that's also a property, when
        // that annotation (like these androidx.annotation ones) is valid on more than one
        // target; this flag keeps the old default (matching both the parameter and the
        // property) so lint's @StringRes/@DrawableRes resource-type checking keeps seeing
        // the annotation wherever those properties are read, not only at construction.
        // The alternative is adding explicit @param:/@property: site-targets to every one
        // of those properties instead of relying on the default - a mechanical rewrite
        // that's straightforward in principle, but isn't safe to do blindly without a
        // compiler available here to confirm each call site still resolves afterward.
        freeCompilerArgs.addAll("-XXLanguage:+PropertyParamAnnotationDefaultTargetMode")
    }
}

// Release signing credentials: never hardcoded, and never required for a contributor to
// build the project. Same source-of-truth pattern already used below for the AdMob IDs -
// local.properties for local development (gitignored, machine-specific), or environment
// variables for CI, where a keystore file itself would also need to be provisioned
// separately (e.g. decoded from a CI secret to the path RELEASE_STORE_FILE points at).
val releaseProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}
fun releaseSigningProperty(localKey: String, envKey: String): String? =
    System.getenv(envKey) ?: releaseProperties.getProperty(localKey)

val releaseStoreFile = releaseSigningProperty("release.store.file", "RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningProperty("release.store.password", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningProperty("release.key.alias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningProperty("release.key.password", "RELEASE_KEY_PASSWORD")
val hasReleaseSigningConfig = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.leekleak.trafficlight"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.leekleak.trafficlight"
        minSdk = 28
        targetSdk = 37
        versionCode = 49
        versionName = "3.1"
        base.archivesName = "$namespace-$versionName"

        manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
    }
    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Falls back to debug signing only when no real release credentials are
            // configured, so the project still builds for contributors who haven't set
            // any up. A build produced this way is unmistakably a debug-signed release
            // build (Android won't accept it as an update to a real release-signed
            // install), so this can't silently ship as a production release by accident.
            signingConfig = if (hasReleaseSigningConfig) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "No release signing credentials found (set RELEASE_STORE_FILE / " +
                    "RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD as " +
                    "environment variables, or release.store.file / release.store.password / " +
                    "release.key.alias / release.key.password in local.properties). " +
                    "Falling back to debug signing for this release build."
                )
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }
    flavorDimensions += "version"
    productFlavors {
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(localPropertiesFile.inputStream())
        }

        val admobAppId = localProperties.getProperty("admob.app.id") ?: "ca-app-pub-3940256099942544~3347511713"
        val admobUnitIdOverview = localProperties.getProperty("admob.unit.id.overview") ?: "/21775744923/example/native"

        create("full") {
            dimension = "version"
            buildConfigField("Boolean", "ADS", "false")
        }
        create("play") {
            dimension = "version"
            buildConfigField("Boolean", "ADS", "true")
            buildConfigField("String", "ADMOB_APP_ID", "\"$admobAppId\"")
            buildConfigField("String", "ADMOB_UNIT_ID_OVERVIEW", "\"$admobUnitIdOverview\"")
            manifestPlaceholders["admobAppId"] = admobAppId
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
    lint {
        abortOnError = false
    }
}

aboutLibraries {
    library {
        duplicationMode = DuplicateMode.MERGE
        duplicationRule = DuplicateRule.GROUP
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)

    implementation(libs.kotlinx.serialization.json)

    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.compose.navigation3)
    implementation(libs.androidx.compose.adaptive)

    implementation(libs.androidx.lifecycle.service)

    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)

    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.timber)

    implementation(libs.coil.compose)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.preview)

    /**
     * Not actually used explicitly, however it is used by glance widgets.
     * The problem is that the version that the latest glance widget release uses is 2.7.1
     * which _for some reason_ breaks after R8.
     *
     * Without this manual import the widget still works fine on debug but on release it never loads
     * throwing WorkManager errors into the logs.
     *
     * Really, really stupid and I hope I'll be able to remove this some time in the future.
     */
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.materials)
    "playImplementation"(project(":playIntegration"))
    "playImplementation"(libs.ads.mobile.sdk)

    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.core)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
