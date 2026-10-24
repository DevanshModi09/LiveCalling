plugins { id("com.android.application") }
android {
    namespace = "com.vcd.livecalling"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.vcd.livecalling"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { implementation(project(":")) }
