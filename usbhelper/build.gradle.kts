plugins {
    id("com.android.application")
}

android {
    namespace = "com.carlink.usbhelper"
    compileSdk = 36

    defaultConfig {
        applicationId = "android.car.usb.handler"
        minSdk = 29
        targetSdk = 32
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":usbbridge"))
    implementation("androidx.core:core-ktx:1.18.0")
}
