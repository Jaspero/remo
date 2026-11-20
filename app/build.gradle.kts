plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "co.jaspero.remo"
    compileSdk = 35

    defaultConfig {
        applicationId = "co.jaspero.remo"
        minSdk = 26
        targetSdk = 35
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "1.0.0"
    }

    signingConfigs {
        // The release key lives outside git: secrets/remo-release.jks with its password in
        // secrets/remo-release.env (REMO_KEYSTORE_PASSWORD). See build-release.sh.
        create("release") {
            System.getenv("REMO_KEYSTORE_FILE")?.takeIf { it.isNotEmpty() }?.let { keystore ->
                storeFile = file(keystore)
                storePassword = System.getenv("REMO_KEYSTORE_PASSWORD")
                keyAlias = "remo"
                keyPassword = System.getenv("REMO_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (!System.getenv("REMO_KEYSTORE_FILE").isNullOrEmpty())
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += listOf(
            "META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Only used once, to make the self-signed certificate the TV knows this phone by.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")

    testImplementation("junit:junit:4.13.2")
}
