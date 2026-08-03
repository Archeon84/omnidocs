import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.omnidocs.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.omnidocs.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // Only build for 64-bit ARM — the target device (Xiaomi 13 Ultra) is arm64,
            // and the ARM arch flags (armv8.6-a+dotprod+i8mm) are 64-bit only.
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++11 -frtti -fexceptions -Wno-format")
                arguments("-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_shared", "-DANDROID_ARM_NEON=TRUE")
                abiFilters("arm64-v8a")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.0.12077973"

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/**"
        }
    }
}

configurations.all {
    // Resolve BouncyCastle conflict between itext7 (jdk15on) and
    // flexmark-all (jdk15to18) — both contain overlapping classes.
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    exclude(group = "org.bouncycastle", module = "bcpkix-jdk15to18")
    exclude(group = "org.bouncycastle", module = "bcutil-jdk15to18")
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Room + SQLCipher
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:android-database-sqlcipher:4.5.3")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // OkHttp for API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Google Sign-In
    implementation("com.google.android.gms:play-services-auth:21.0.0")
    implementation("com.google.android.gms:play-services-auth-api-phone:18.0.2")

    // ML Kit - Document Scanner
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // CameraX
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")

    // Accompanist Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // PDF generation
    implementation("com.itextpdf:itext7-core:7.2.5")

    // DOC generation & import (Apache POI)
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5")
    implementation("org.apache.poi:poi-scratchpad:5.2.5")

    // HTML Sanitization (Jsoup)
    implementation("org.jsoup:jsoup:1.17.2")

    // PDF text extraction
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Markdown → HTML conversion
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")

    // Kotlinx Serialization for model checksums
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}

// Paddle Lite native libraries download
val paddleArchives = listOf(
    mapOf(
        "src" to "https://paddleocr.bj.bcebos.com/libs/paddle_lite_libs_v2_10.tar.gz",
        "dest" to "${project.projectDir}/PaddleLite"
    )
)

tasks.register("downloadPaddleLite", DefaultTask::class) {
    doFirst {
        println("Downloading Paddle Lite native libraries")
    }
    doLast {
        val cachePath = file("${buildDir}/cache")
        if (!cachePath.exists()) {
            cachePath.mkdirs()
        }
        paddleArchives.forEach { archive ->
            val messageDigest = MessageDigest.getInstance("MD5")
            messageDigest.update(archive["src"]!!.toByteArray())
            val cacheName = BigInteger(1, messageDigest.digest()).toString(32)
            val destFile = file(archive["dest"]!!)
            var copyFiles = !destFile.exists()
            val cacheFile = file("${cachePath}/${cacheName}.tar.gz")
            if (!cacheFile.exists()) {
                // Use ant builder for downloading
                val antBuilder = org.apache.tools.ant.Project()
                antBuilder.init()
                val getTask = org.apache.tools.ant.taskdefs.Get()
                getTask.setProject(antBuilder)
                getTask.setSrc(URL(archive["src"]!!))
                getTask.setDest(cacheFile)
                getTask.execute()
                copyFiles = true
            }
            if (copyFiles) {
                copy {
                    from(tarTree(cacheFile))
                    into(archive["dest"]!!)
                }
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn("downloadPaddleLite")
}

// Copy PaddleLite .so files to jniLibs so they get packaged in the APK
tasks.register("copyPaddleLiteLibs", Copy::class) {
    dependsOn("downloadPaddleLite")
    from("${project.projectDir}/PaddleLite/java/libs") {
        include("**/*.so")
    }
    into("${project.projectDir}/src/main/jniLibs")
}

tasks.named("preBuild") {
    dependsOn("copyPaddleLiteLibs")
}