plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace="app.lumo"; compileSdk=35
 signingConfigs {
  create("release") {
   val storePath=System.getenv("LUMO_KEYSTORE_PATH")
   if(!storePath.isNullOrBlank()){
    storeFile=file(storePath)
    storePassword=System.getenv("LUMO_KEYSTORE_PASSWORD")
    keyAlias=System.getenv("LUMO_KEY_ALIAS")
    keyPassword=System.getenv("LUMO_KEY_PASSWORD")
   }
  }
 }
 defaultConfig {
  applicationId="app.lumo"; minSdk=26; targetSdk=35
  versionCode=(System.getenv("LUMO_VERSION_CODE")?.toIntOrNull() ?: 18)
  versionName="0.1."+versionCode
 }
 buildTypes {
  getByName("release"){isMinifyEnabled=false;signingConfig=signingConfigs.getByName("release")}
 }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }
 buildFeatures { compose=true; buildConfig=true }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.01.01"))
 implementation("androidx.activity:activity-compose:1.10.0")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 // Draft-only WebRTC audio prototype, not available in the published Lumo app.
 implementation("io.github.webrtc-sdk:android:150.7871.01")
 testImplementation("junit:junit:4.13.2")
}