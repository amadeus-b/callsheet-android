import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version und Signatur stehen ausserhalb dieser Datei: die Version, damit das
// Release-Skript sie hochzaehlen kann, ohne Gradle-Code anzufassen; der
// Schluessel, weil er nie ins Repository gehoert.
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}

android {
    namespace = "io.github.amadeusb.callsheet"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.amadeusb.callsheet"
        minSdk = 30
        targetSdk = 37
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Ohne keystore.properties faellt der Build auf den Debug-Schluessel
        // zurueck. Zum Ausprobieren genuegt das; veroeffentlichte Releases
        // brauchen denselben Schluessel wie ihre Vorgaenger, sonst verweigert
        // Android das Update.
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.sqlite:sqlite-ktx:2.6.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")


    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
}

// Robolectric lädt seine Android-Laufzeit sonst selbst aus dem Netz nach; das
// scheitert in abgeschotteten Umgebungen. Wir lassen Gradle das JAR auflösen und
// betreiben Robolectric offline.
val robolectricSdks: Configuration by configurations.creating

dependencies {
    robolectricSdks("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
}

val robolectricVerzeichnis = layout.buildDirectory.dir("robolectric-sdks")

val stelleRobolectricBereit by tasks.registering(Copy::class) {
    from(robolectricSdks)
    into(robolectricVerzeichnis)
}

tasks.withType<Test>().configureEach {
    dependsOn(stelleRobolectricBereit)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricVerzeichnis.get().asFile.absolutePath)
}
