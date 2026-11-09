import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Configuração do servidor: local.properties (não versionado) ou variáveis de ambiente do CI.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun cfg(name: String): String = (localProps.getProperty(name) ?: System.getenv(name) ?: "").replace("\"", "").trim()

// Chave de assinatura da versão de distribuição: arquivo fora do projeto (RELEASE_KEYSTORE_PROPERTIES).
val releaseProps = Properties().apply {
    cfg("RELEASE_KEYSTORE_PROPERTIES").takeIf { it.isNotEmpty() }?.let { file(it) }?.takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}

android {
    namespace = "br.com.intelientrega"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.intelientrega"
        minSdk = 26 // TYPE_APPLICATION_OVERLAY exige API 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.3"
        // Só ARM: todo celular Android de verdade usa. x86 era só para emulador de PC e dobrava o tamanho.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // Valores PÚBLICOS: URL do projeto, chave anon/publishable e chave pública do comprovante.
        // Nunca coloque aqui a service_role, o token do Mercado Pago ou a chave privada.
        buildConfigField("String", "SUPABASE_URL", "\"${cfg("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cfg("SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "ENTITLEMENT_PUBLIC_KEY", "\"${cfg("ENTITLEMENT_PUBLIC_KEY")}\"")
        // Arquivo público com a versão mais nova (o app é distribuído fora da Play Store).
        buildConfigField("String", "UPDATE_URL", "\"${cfg("UPDATE_URL")}\"")
    }

    signingConfigs {
        if (releaseProps.getProperty("storeFile") != null) create("release") {
            storeFile = file(releaseProps.getProperty("storeFile"))
            storePassword = releaseProps.getProperty("storePassword")
            keyAlias = releaseProps.getProperty("keyAlias")
            keyPassword = releaseProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    // ML Kit com modelo latino EMBUTIDO no APK: OCR 100% local, sem download de modelo.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // org.json do android.jar é só stub nos testes de JVM.
    testImplementation(libs.json)
}
