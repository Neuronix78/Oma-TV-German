plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Beim Bau auf GitHub wird die Adresse der Senderliste automatisch
// aus dem Namen des Repositorys gebildet (Datei sender.m3u im Hauptordner).
val githubRepo = System.getenv("GITHUB_REPOSITORY") ?: ""
val playlistUrl =
    if (githubRepo.isNotEmpty()) "https://raw.githubusercontent.com/$githubRepo/main/sender.m3u" else ""
// Gesamtliste aller Sender, wird jeden Morgen von GitHub neu gebaut (alle-sender.json)
val catalogUrl =
    if (githubRepo.isNotEmpty()) "https://raw.githubusercontent.com/$githubRepo/main/alle-sender.json" else ""

// Jede neue Version bekommt eine höhere Nummer, damit Updates sauber installiert werden.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

val keystoreFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "de.familie.fernsehen"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.familie.fernsehen"
        minSdk = 21
        targetSdk = 34
        versionCode = buildNumber
        versionName = "2.0.$buildNumber"
        buildConfigField("String", "PLAYLIST_URL", "\"$playlistUrl\"")
        buildConfigField("String", "CATALOG_URL", "\"$catalogUrl\"")
    }

    // Zwei Varianten aus demselben Code:
    //  voll = ersetzt den Startbildschirm der Box (für Mama)
    //  lite = ganz normale App, die man öffnen und wieder schließen kann
    flavorDimensions += "variante"
    productFlavors {
        create("voll") {
            dimension = "variante"
            buildConfigField("boolean", "IS_LAUNCHER", "true")
            resValue("string", "app_name", "OmaTV")
        }
        create("lite") {
            dimension = "variante"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
            buildConfigField("boolean", "IS_LAUNCHER", "false")
            resValue("string", "app_name", "OmaTV Lite")
        }
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "fernsehen"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.coil-kt:coil:2.7.0")
}
