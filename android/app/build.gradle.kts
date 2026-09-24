plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }

// Push remains an explicit debug-staging feature. A normal CI/debug build uses
// the production API, does not apply google-services, and never auto-creates an
// FCM token. A staging build must use a DIFFERENT HTTPS backend and package id.
val normalHttp = "https://lumo-gamma-seven.vercel.app"
val stagingBase = providers.gradleProperty("lumoStagingUrl").orNull?.trimEnd('/')
if (stagingBase != null) {
 require(stagingBase != normalHttp) { "Experimental staging cannot use the production Lumo API" }
 require(Regex("^https://[a-zA-Z0-9.-]+(:[0-9]{2,5})?$").matches(stagingBase)) {
  "Use a dedicated HTTPS staging origin (no credentials or URL paths)"
 }
}
val fcmRequested = providers.gradleProperty("lumoEnableFcm").orNull == "true"
val fcmConfigured = fcmRequested && stagingBase != null && file("google-services.json").isFile
if (fcmRequested && !fcmConfigured)
 throw GradleException("FCM lab needs android/app/google-services.json and -PlumoStagingUrl=https://staging.example")
if (fcmConfigured) apply(plugin="com.google.gms.google-services")
val httpForDebug = stagingBase ?: normalHttp
val wsForDebug = httpForDebug.replaceFirst("https://", "wss://") + "/ws"
val releaseSigningReady = listOf(
 System.getenv("LUMO_KEYSTORE_PATH"),
 System.getenv("LUMO_KEYSTORE_PASSWORD"),
 System.getenv("LUMO_KEY_ALIAS"),
 System.getenv("LUMO_KEY_PASSWORD")
).all { !it.isNullOrBlank() }

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
  versionCode=(System.getenv("LUMO_VERSION_CODE")?.toIntOrNull() ?: 1004)
  versionName=System.getenv("LUMO_VERSION_NAME")?.takeIf{it.isNotBlank()} ?: "1.0.4"
 }
 buildTypes {
  getByName("debug") {
   if (stagingBase != null) applicationIdSuffix=".staging"
   buildConfigField("String","LUMO_HTTP_BASE","\"$httpForDebug\"")
   buildConfigField("String","LUMO_WS_BASE","\"$wsForDebug\"")
   buildConfigField("boolean","LUMO_FCM_CONFIGURED",fcmConfigured.toString())
  }
  getByName("release") {
   isMinifyEnabled=false
   if(releaseSigningReady) signingConfig=signingConfigs.getByName("release")
   buildConfigField("String","LUMO_HTTP_BASE","\"$normalHttp\"")
   buildConfigField("String","LUMO_WS_BASE","\"wss://lumo-gamma-seven.vercel.app/ws\"")
   buildConfigField("boolean","LUMO_FCM_CONFIGURED","false")
  }
 }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }
 buildFeatures { compose=true; buildConfig=true }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.01.01"))
 implementation("androidx.activity:activity-compose:1.10.0")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.animation:animation-core")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
 // Staging WebRTC audio transport; UI still requires explicit accepted call + mic permission + TURN.
 implementation("io.github.webrtc-sdk:android:150.7871.01")

 // Debug only: no provider configuration or token generation unless owner
 // supplies explicit staging flags and a local google-services.json.
 debugImplementation(platform("com.google.firebase:firebase-bom:34.19.0"))
 debugImplementation("com.google.firebase:firebase-messaging")
 debugImplementation("androidx.work:work-runtime-ktx:2.10.0")


 testImplementation("junit:junit:4.13.2")
}