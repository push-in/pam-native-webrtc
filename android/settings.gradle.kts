// Standalone harness for compiling, linting and running the plugin's instrumented
// tests on an emulator. PAM applications ignore this file: the PAM CLI generates
// its own Gradle module from pam-native.plugin.json.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pam-native-webrtc-android"

val pamNativeAndroid = providers.gradleProperty("pamNativeAndroid")
    .orElse(rootDir.resolve("../../../pam-native/android").path)
    .get()
include(":plugin-api")
project(":plugin-api").projectDir = file("$pamNativeAndroid/plugin-api")
