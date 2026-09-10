import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services) apply false
}

// Offline-first public builds: anyone cloning the repo gets a fully local
// app (no Firebase project needed). The google-services plugin only applies
// when app/google-services.json exists; otherwise (or with -PforceOffline)
// the build compiles with OFFLINE_MODE=true and every cloud feature is
// gated out at runtime. Full builds (with your own google-services.json)
// are unaffected.
val hasGoogleServices = file("google-services.json").exists()
val forceOffline = project.hasProperty("forceOffline")
val offlineBuild = !hasGoogleServices || forceOffline
if (hasGoogleServices && !forceOffline) {
    apply(plugin = "com.google.gms.google-services")
}
if (offlineBuild) {
    println("BroDue: building OFFLINE variant (no google-services.json / -PforceOffline)")
}

// Worker shared secret lives in gitignored local.properties
// (worker.appSecret=...), never in source. Absent (cloned repos) -> empty,
// which only disables worker-authenticated calls.
val workerAppSecret: String = try {
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) FileInputStream(f).use { load(it) }
    }.getProperty("worker.appSecret", "")
} catch (_: Exception) {
    ""
}

// Signing secrets live in gitignored keystore.properties (see .gitignore)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.abk.brodue"
    compileSdk {
        version = release(37)
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = keystoreProps["storeFile"]?.let { file(it) }
                storePassword = keystoreProps["storePassword"] as String?
                keyAlias = keystoreProps["keyAlias"] as String?
                keyPassword = keystoreProps["keyPassword"] as String?
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.abk.brodue"
        minSdk = 24
        targetSdk = 37
        versionCode = 1

        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "OFFLINE_MODE", offlineBuild.toString())
        buildConfigField("String", "WORKER_APP_SECRET", "\"${workerAppSecret.replace("\"", "\\\"")}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.coil)
    implementation(libs.biometric)
    implementation(libs.zxing)
    implementation(libs.work.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}