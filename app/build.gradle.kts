plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.grabadora.agente"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.grabadora.agente"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1"

        // Vosk/JNA traen librerías nativas para 7 arquitecturas (incluidas mips y armeabi, ya sin
        // teléfonos). Solo empaquetamos las que existen hoy: arm64 (casi todos los teléfonos),
        // armeabi-v7a (teléfonos de 32 bits) y x86_64 (emulador). Baja el tamaño del APK.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // El modelo de voz de Vosk va en assets; no lo comprimimos para que se copie rápido
    // y para que ModeloManager pueda saber el tamaño de cada archivo sin leerlo entero.
    androidResources {
        noCompress += listOf("mdl", "fst", "conf", "int", "mat", "txt", "uuid", "ie", "dubm", "stats")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")

    // Reconocimiento de voz offline para la palabra clave (no viene en el libro)
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
