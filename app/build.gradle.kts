plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
  namespace = "app.lunar.injector"; compileSdk = 34
  defaultConfig { applicationId = "app.lunar.injector"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "0.1" }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  buildFeatures { compose = true }
  packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES") }
}
dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.09.00"))
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("org.cloudburstmc.protocol:bedrock-connection:3.0.0.Beta13-SNAPSHOT")
  implementation("org.cloudburstmc.protocol:bedrock-codec:3.0.0.Beta13-SNAPSHOT")
}
