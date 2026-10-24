plugins {
    id("com.android.library") version "9.4.1"
    id("com.android.application") version "9.4.1" apply false
}
android {
    namespace = "com.vcd.calling"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { testImplementation("junit:junit:4.13.2") }
