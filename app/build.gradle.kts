import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
}

// Tutto ciò che è personale sta in keystore/ (fuori da git) e riguarda solo l'edizione "prv".
fun props(name: String) =
    Properties().apply {
      val f = rootDir.resolve("keystore/$name")
      if (f.exists()) f.inputStream().use { load(it) }
    }

// Chiave di firma dedicata: la sua SHA-256 è registrata nel Wearables Developer Center
val signing = props("keystore.properties")
// Credenziali del Wearables Developer Center. Senza file: "0" = Developer Mode.
val mwdat = props("mwdat.properties")
// applicationId e valori predefiniti delle impostazioni
val prv = props("prv.properties")

// Nome dell'app: in Developer Mode è anche il nome da dire dopo "Hey Meta, open …"
val appName = providers.gradleProperty("appName").orNull ?: "Hey Casa!!"

fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
  namespace = "it.homelab.casa"
  compileSdk = 36

  signingConfigs {
    if (signing.getProperty("storeFile") != null) {
      create("prv") {
        storeFile = rootDir.resolve(signing.getProperty("storeFile"))
        storePassword = signing.getProperty("storePassword")
        keyAlias = signing.getProperty("keyAlias")
        keyPassword = signing.getProperty("keyPassword")
      }
    }
  }

  defaultConfig {
    minSdk = 31
    targetSdk = 36
    versionCode = 13
    versionName = "1.1.0"
    resValue("string", "app_name", appName)

    // Solo telefoni a 64 bit: dimezza l'APK
    ndk { abiFilters += "arm64-v8a" }
  }
  buildFeatures { buildConfig = true }

  // Stesso codice, due edizioni: quella pubblica senza alcun dato e quella personale.
  flavorDimensions += "edizione"
  productFlavors {
    create("pubblica") {
      dimension = "edizione"
      applicationId = "io.github.locafra.heycasa"
      // Developer Mode: attestation non usata, valori a 0
      manifestPlaceholders["mwdat_application_id"] = "0"
      manifestPlaceholders["mwdat_client_token"] = "0"
      buildConfigField("String", "HA_URL_DEF", quoted("http://homeassistant.local:8123"))
      buildConfigField("String", "HA_URL_REMOTE_DEF", quoted(""))
      buildConfigField("String", "LLM_AGENT_DEF", quoted(""))
      signingConfig = signingConfigs.getByName("debug")
    }
    create("prv") {
      dimension = "edizione"
      applicationId = prv.getProperty("application_id", "io.github.locafra.heycasa.prv")
      versionNameSuffix = "prv"
      resValue("string", "app_name", prv.getProperty("app_name", appName))
      // Release channel (Dev Mode OFF): ID e token del progetto nel Developer Center
      manifestPlaceholders["mwdat_application_id"] = mwdat.getProperty("mwdat_application_id", "0")
      manifestPlaceholders["mwdat_client_token"] = mwdat.getProperty("mwdat_client_token", "0")
      buildConfigField(
          "String", "HA_URL_DEF", quoted(prv.getProperty("ha_url", "http://homeassistant.local:8123")))
      buildConfigField("String", "HA_URL_REMOTE_DEF", quoted(prv.getProperty("ha_url_remote", "")))
      buildConfigField("String", "LLM_AGENT_DEF", quoted(prv.getProperty("llm_agent", "")))
      signingConfig = signingConfigs.findByName("prv") ?: signingConfigs.getByName("debug")
    }
  }

  buildTypes {
    // la firma la decide l'edizione
    debug { signingConfig = null }
    release { isMinifyEnabled = false }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
  // Le schermate del README le disegna Robolectric (ScreenshotTest), senza telefono né emulatore
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.speech)
  implementation(libs.androidx.activity)
  implementation(libs.androidx.lifecycle.runtime)
  implementation(libs.androidx.lifecycle.process)
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
}
