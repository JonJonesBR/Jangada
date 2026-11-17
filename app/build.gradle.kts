plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A chave de assinatura vem dos "secrets" do GitHub (nunca do repositório).
// Sem ela (forks, pull requests), o APK sai assinado com a chave de debug.
val signingStoreFile: String? = System.getenv("SIGNING_STORE_FILE")

android {
    namespace = "io.github.jonjonesbr.jangada"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jonjonesbr.jangada"
        minSdk = 26
        // 28 de propósito, a mesma escolha do Termux: permite executar os programas
        // do ambiente Linux (Etapa 2) a partir da pasta do app. Ver README.
        targetSdk = 28
        versionCode = 134
        versionName = "1.28.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testProguardFiles("proguard-testapk.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            if (signingStoreFile != null) {
                storeFile = file(signingStoreFile)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 remove código e recursos não usados (APK menor). Não ofusca: o repositório é
            // público e o last_crash.txt precisa continuar legível (ver proguard-rules.pro).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (signingStoreFile != null) "release" else "debug")
        }
        // Igual ao release (mesmo R8, mesmo corte de recursos), mas assinado com a chave de debug e com
        // regras extras que mantêm só o que os testes do emulador chamam direto. Serve para rodar a suíte
        // do emulador no APK minificado (ui-tests.yml, variante "minificado"). Não é publicado.
        create("minified") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            proguardFiles("proguard-androidtest.pro")
        }
    }

    // Com -PtestarMinificado os testes do emulador rodam contra o build type "minified".
    if (project.hasProperty("testarMinificado")) testBuildType = "minified"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        // Testes rodam na JVM do CI; chamadas ao Android (Log etc.) devolvem o padrão.
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(project(":terminal-view"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    testImplementation("junit:junit:4.13.2")
    // O org.json do Android não funciona fora do celular; nos testes, usa a biblioteca original.
    testImplementation("org.json:json:20260814")

    // Testes de interface (app/src/androidTest), rodados no emulador do CI (ver .github/workflows/ui-tests.yml).
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
}

// Mostra no log do CI o motivo de cada teste que falhar.
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
