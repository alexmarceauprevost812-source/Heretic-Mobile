plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "ca.heretic.mobile"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "ca.heretic.mobile"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "0.2.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core")); implementation(project(":data")); implementation(project(":inference"))
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
