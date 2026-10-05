plugins {
    id("com.android.library") version "9.2.0"
}

android {
    namespace = "dev.pam.webrtc"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}

dependencies {
    api(project(":plugin-api"))
    implementation("org.jitsi:webrtc:124.0.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

// Keep framework sources read-only: plugin-api outputs live in this harness.
subprojects {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("subprojects/$name"))
}
