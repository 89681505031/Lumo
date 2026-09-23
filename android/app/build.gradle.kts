plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
// The public debug CI build stays Firebase-free without explicit local staging setup.
val stagingBase = providers.gradleProperty("lumoStagingUrl").orNull?.trimEnd('/')
if (stagingBase != null) {
 require(Regex("^https://[a-zA-Z0-9.-]+(:[0-9]{2,5})?$").matches(stagingBase)) {
  "Use a dedicated HTTPS staging origin (no credentials or URL paths)"
 }
}
val fcmRequested = providers.gradleProperty("lumoEnableFcm").orNull == "true"
val fcmConfigured = fcmRequested && stagingBase != null && file("google-services.json").isFile
if (fcmRequested && !fcmConfigured)
 throw GradleException("FCM lab needs android/app/google-services.json and -PlumoStagingUrl=https://staging.example")
if (fcmConfigured) apply(plugin="com.google.gms.google-services")
val normalHttp = "https://lumo-gamma-seven.vercel.app"
val httpForDebug = stagingBase ?: normalHttp
val wsForDebug = httpForDebug.replaceFirst("https://", "wss://") + "/ws"

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
  getByName("debug") {
   if (stagingBase != null) applicationIdSuffix=".staging"
   buildConfigField("String","LUMO_HTTP_BASE", "\"$httpForDebug\"")
   buildConfigField("String","LUMO_WS_BASE", "\"$wsForDebug\"")
   buildConfigField("boolean","LUMO_FCM_CONFIGURED",fcmConfigured.toString())
  }
  getByName("release") {
   isMinifyEnabled=false
   signingConfig=signingConfigs.getByName("release")
   buildConfigField("String","LUMO_HTTP_BASE", "\"$normalHttp\"")
   buildConfigField("String","LUMO_WS_BASE", "\"wss://lumo-gamma-seven.vercel.app/ws\"")
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
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 debugImplementation(platform("com.google.firebase:firebase-bom:34.19.0"))
 debugImplementation("com.google.firebase:firebase-messaging")
}