import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import org.jetbrains.dokka.gradle.engine.parameters.KotlinPlatform
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.gradle.api.tasks.Optional

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
}

val javaTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())

abstract class GenerateGitHashTask : DefaultTask() {

    // NOIR: @Optional ditambahkan. Tanpa ini, Gradle MEMVALIDASI keberadaan
    // .git/HEAD sebelum task jalan dan menggagalkan build — padahal generate()
    // di bawah sudah menangani ketiadaan .git dengan hash kosong.
    // Akibatnya: build dari zip sumber (yang tidak membawa .git/) selalu gagal.
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headFile: RegularFileProperty

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val head = headFile.get().asFile

        val hash = try {
            if (head.exists()) {
                // Read the commit hash from .git/HEAD
                val headContent = head.readText().trim()
                if (headContent.startsWith("ref:")) {
                    val refPath = headContent.substring(5) // e.g., refs/heads/main
                    val commitFile = File(head.parentFile, refPath)
                    if (commitFile.exists()) commitFile.readText().trim() else ""
                } else headContent // If it's a detached HEAD (commit hash directly)
            } else "" // If .git/HEAD doesn't exist
        } catch (_: Throwable) {
            "" // Just set to an empty string if any exception occurs
        }.take(7) // Get the short commit hash

        val outFile = outputDir.file("git-hash.txt").get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(hash)
    }
}

val generateGitHash = tasks.register<GenerateGitHashTask>("generateGitHash") {
    val gitDir = layout.projectDirectory.dir("../.git")

    headFile.set(gitDir.file("HEAD"))
    headsDir.set(gitDir.dir("refs/heads"))

    outputDir.set(layout.buildDirectory.dir("generated/git"))
}

// ===== Noir: helper untuk resValue commit_hash (dipakai UI Noir) =====
fun getGitCommitHash(): String {
    return try {
        val headFile = file("${project.rootDir}/.git/HEAD")
        if (headFile.exists()) {
            val headContent = headFile.readText().trim()
            if (headContent.startsWith("ref:")) {
                val refPath = headContent.substring(5).trim()
                val commitFile = file("${project.rootDir}/.git/$refPath")
                if (commitFile.exists()) commitFile.readText().trim() else ""
            } else headContent
        } else {
            ""
        }.take(7)
    } catch (_: Throwable) {
        ""
    }
}

android {
    @Suppress("UnstableApiUsage")
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidComponents {
        onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(
                generateGitHash,
                GenerateGitHashTask::outputDir
            )
        }
    }

    // ===== NOIR: signing release =====
    // PENTING: password fallback milik fork lama sudah DIHAPUS (bocor di repo publik).
    // Isi lewat GitHub Secrets / local.properties. Lihat BUILD-DI-HP.md.
    signingConfigs {
        create("release") {
            val envKeystorePath = System.getenv("KEYSTORE_PATH")
            storeFile = if (envKeystorePath != null) file(envKeystorePath) else file("keystore.jks")
            storePassword = System.getenv("KEY_STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("ALIAS") ?: "noir"
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    // ===== NOIR: hanya paketkan locale en/id/in =====
    androidResources {
        localeFilters += listOf("en", "id", "in")
    }

    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = "27.2.12479018"

    defaultConfig {
        // ===== NOIR: identitas aplikasi =====
        // applicationId baru = aplikasi TERPISAH dari Noir.
        // Tidak bisa di-install menimpa; harus uninstall yang lama dulu.
        applicationId = "com.noir.stream"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1           // NOIR: mulai dari awal
        versionName = "1.0.0"     // NOIR: versi brand baru

        manifestPlaceholders["target_sdk_version"] = libs.versions.targetSdk.get()

        // ===== NOIR: resource runtime =====
        resValue("string", "commit_hash", getGitCommitHash())
        resValue("bool", "is_prerelease", "false")
        resValue("string", "app_name", "Noir")
        resValue("color", "blackBoarder", "#FF000000")

        // Reads local.properties
        val localProperties = gradleLocalProperties(rootDir, project.providers)

        buildConfigField(
            "long",
            "BUILD_DATE",
            "${System.currentTimeMillis()}"
        )
        // ===== NOIR: versi aplikasi untuk UI =====
        buildConfigField("String", "APP_VERSION", "\"$versionName\"")

        // ===== NOIR: SUMBER UPDATE IN-APP =====
        // WAJIB diganti ke repo GitHub KAMU, kalau tidak aplikasi akan
        // mengecek update ke repo fork lama.
        // Cara isi: buat local.properties di root proyek, tambahkan:
        //     UPDATE_GITHUB_USER=usernamekamu
        //     UPDATE_GITHUB_REPO=nama-repo-kamu
        // Atau set sebagai environment variable / GitHub Secrets.
        buildConfigField(
            "String",
            "UPDATE_GITHUB_USER",
            "\"" + (System.getenv("UPDATE_GITHUB_USER")
                ?: localProperties.getProperty("UPDATE_GITHUB_USER")
                ?: "USERNAME_KAMU") + "\""
        )
        buildConfigField(
            "String",
            "UPDATE_GITHUB_REPO",
            "\"" + (System.getenv("UPDATE_GITHUB_REPO")
                ?: localProperties.getProperty("UPDATE_GITHUB_REPO")
                ?: "REPO_KAMU") + "\""
        )

        // ===== Noir: kunci SIMKL di-hardcode =====
        buildConfigField(
            "String",
            "SIMKL_CLIENT_ID",
            "\"db13c9a72e036f717c3a85b13cdeb31fa884c8f4991e43695f7b6477374e35b8\""
        )
        buildConfigField(
            "String",
            "SIMKL_CLIENT_SECRET",
            "\"d8cf8e1b79bae9b2f77f0347d6384a62f1a8d802abdd73d9aa52bf6a848532ba\""
        )
        buildConfigField(
            "String",
            "MAL_KEY",
            "\"" + (System.getenv("MAL_KEY") ?: localProperties["mal.key"]) + "\""
        )
        buildConfigField(
            "String",
            "ANILIST_KEY",
            "\"" + (System.getenv("ANILIST_KEY") ?: localProperties["anilist.key"]) + "\""
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Build the offline GGUF runtime only for 64-bit ABIs. Do not set
        // ndk.abiFilters: the rest of Noir may still package its legacy 32-bit
        // native libraries, while local Hy-MT2 reports unsupported on 32-bit.
        externalNativeBuild {
            cmake {
                abiFilters += setOf("arm64-v8a", "x86_64")
                targets += listOf("noir_llama")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions.add("state")
    productFlavors {
        create("stable") {
            dimension = "state"
            resValue("bool", "is_prerelease", "false")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.toVersion(javaTarget.target)
        targetCompatibility = JavaVersion.toVersion(javaTarget.target)
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(libs.versions.jdkToolchain.get()))
        }
    }

    lint {
        checkReleaseBuilds = false
        disable.add("MissingTranslation")
    }

    buildFeatures {
        buildConfig = true
        resValues = true
        viewBinding = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    namespace = "com.lagradost.cloudstream3"
}

dependencies {
    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.instancio.core)
    androidTestImplementation(libs.junit.ktx)
    androidTestImplementation(libs.kotlin.test)

    // Android Core & Lifecycle
    implementation(libs.core.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.annotation)
    implementation(libs.appcompat)
    implementation(libs.fragment.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.navigation)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.serialization.json)

    // Design & UI
    implementation(libs.preference.ktx)
    implementation(libs.material)
    implementation(libs.constraintlayout)

    // Coil Image Loading
    implementation(libs.bundles.coil)

    // Media 3 (ExoPlayer)
    implementation(libs.bundles.media3)
    implementation(libs.video)

    // FFmpeg Decoding
    implementation(libs.bundles.nextlib)

    // Anime-db for filler
    implementation(libs.anime.db)

    // PlayBack
    implementation(libs.colorpicker)
    implementation(libs.newpipeextractor)
    // NOIR fix15: runtime JS untuk menjalankan extension resmi SpotiFLAC
    // (versi sama dengan yang dibawa NewPipeExtractor — sudah terbukti
    // menjalankan index.js SoundCloud di uji sandbox).
    implementation("org.mozilla:rhino:1.8.1")
    implementation(libs.juniversalchardet)

    // UI Stuff
    implementation(libs.shimmer)
    implementation(libs.palette.ktx)
    implementation(libs.tvprovider)
    implementation(libs.overlappingpanels)
    implementation(libs.biometric)
    implementation(libs.previewseekbar.media3)
    implementation(libs.qrcode.kotlin)

    // Extensions & Other Libs
    implementation(libs.jsoup)
    implementation(libs.ksoup)
    implementation(libs.rhino)
    implementation(libs.safefile)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    implementation(libs.conscrypt.android)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.zipline)

    // ===== Noir: penyimpanan terenkripsi (repo premium) =====

    // Temp/deprecated
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("me.xdrop:fuzzywuzzy:1.4.0")

    // Torrent Support
    implementation(libs.torrentserver)

    // Downloading & Networking
    implementation(libs.work.runtime.ktx)
    implementation(libs.nicehttp)

    implementation(project(":library"))
}

tasks.register<Jar>("androidSourcesJar") {
    archiveClassifier.set("sources")
    from(android.sourceSets.getByName("main").java.directories)
}

tasks.register<Copy>("copyJar") {
    dependsOn("build", ":library:jvmJar")
    from(
        "build/intermediates/compile_app_classes_jar/stableDebug/bundleStableDebugClassesToCompileJar",
        "../library/build/libs"
    )
    into("build/app-classes")
    include("classes.jar", "library-jvm*.jar")
    rename("library-jvm.*.jar", "library-jvm.jar")
}

tasks.register<Jar>("makeJar") {
    duplicatesStrategy = DuplicatesStrategy.FAIL
    dependsOn(tasks.getByName("copyJar"))
    from(
        zipTree("build/app-classes/classes.jar"),
        zipTree("build/app-classes/library-jvm.jar")
    )
    destinationDirectory.set(layout.buildDirectory)
    archiveBaseName = "classes"
}

tasks.withType<KotlinJvmCompile> {
    compilerOptions {
        jvmTarget.set(javaTarget)
        jvmDefault.set(JvmDefaultMode.ENABLE)
        optIn.addAll(
            "com.lagradost.cloudstream3.InternalAPI",
            "com.lagradost.cloudstream3.Prerelease",
            "kotlin.uuid.ExperimentalUuidApi",
        )
    }
}

dokka {
    moduleName = "App"
    dokkaSourceSets {
        configureEach {
            analysisPlatform = KotlinPlatform.JVM
            displayName = "JVM"
            documentedVisibilities(
                VisibilityModifier.Public,
                VisibilityModifier.Protected
            )

            sourceLink {
                localDirectory = file("..")
                remoteUrl("https://github.com/USERNAME_KAMU/REPO_KAMU/tree/master")
                remoteLineSuffix = "#L"
            }
        }
    }
}
