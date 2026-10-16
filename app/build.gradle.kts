import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// -----------------------------------------------------------------------------------------------
// Release signing
//
// Credentials live in `keystore.properties` at the repository root, which is not committed:
//
//   storeFile=release.jks
//   storePassword=<password>
//   keyAlias=release
//   keyPassword=<password>
//
// Create the keystore once, and keep it safe: Android identifies an app by its signing key, so
// losing the key means the app can never be updated in place again.
//
//   keytool -genkeypair -v -keystore release.jks -alias release \
//           -keyalg RSA -keysize 2048 -validity 10000
//
// When `keystore.properties` is absent the release build falls back to the debug key so that
// `assembleRelease` still produces an installable APK. That APK is signed with a public,
// well-known key and must never be published.
// -----------------------------------------------------------------------------------------------
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.driving.hud.usbcamera"
    compileSdk = 34

    // Pin to the NDK that is actually installed (r28c).
    ndkVersion = "28.2.13676358"

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.driving.hud.usbcamera"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "1.0.4"

        ndk {
            // libusb is currently built for arm64-v8a only (the target phone ABI).
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
                cppFlags.addAll(listOf("-std=c++17", "-frtti", "-fexceptions", "-Wall", "-O3"))
                arguments.addAll(listOf("-DANDROID_STL=c++_shared"))
            }
        }
    }

    buildTypes {
        release {
            // Unsigned release builds are not installable, so the release build type must
            // always name a key. See the signing block at the top of this file.
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "keystore.properties not found - signing the release build with the DEBUG " +
                        "key. Fine for testing, never publish this APK."
                )
                signingConfigs.getByName("debug")
            }
            isDebuggable = false
            // R8 is deliberately off. Almost all of the APK is native code (libncnn.so,
            // libonnxruntime.so) plus model assets, none of which R8 touches, while the
            // reflection-heavy dependencies (JNA, ONNX Runtime, the UVCCamera engine) would each
            // need keep rules that can only be validated with a live camera attached. The
            // saving is a couple of megabytes; the risk is a release that crashes on a path the
            // benchmark never exercises. Enable it once there is a device test to run.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Only drop the emulator / 32-bit ABIs now the build is arm64-v8a only. The
            // Qualcomm AAR's per-generation HTP skeletons are gone with the dependency
            // switch, so those excludes are no longer needed.
            excludes += "lib/x86/**"
            excludes += "lib/x86_64/**"
            excludes += "lib/armeabi-v7a/**"
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.kotlinx.coroutines.android)
    // JNA (Java Native Access) — used for raw usbfs ioctls (isochronous UVC capture).
    // This must stay on the @aar variant: the plain JAR ships no Android native libraries, so
    // the ioctl path would fail at runtime on a normal build. Version catalogs cannot express a
    // classifier, hence the @aar on the resolved coordinate.
    implementation("${libs.jna.get()}@aar")
    // UVCCamera engine from jiangdongguo/AndroidUSBCamera (libuvc + libusb +
    // libjpeg-turbo, all bundled) — does UVC negotiation, iso capture and MJPEG
    // decode natively.
    implementation(libs.uvccamera.libuvc)
    // The standard ONNX Runtime build. It does NOT contain the Qualcomm QNN execution provider —
    // that ships separately as `onnxruntime-android-qnn`. The provider list in OnnxDetector is
    // therefore gated on what the runtime reports as actually compiled in, and on the reference
    // tablet the detector settles on ncnn/Vulkan. ONNX Runtime is the fallback, and the only
    // backend available for models that have no converted ncnn graph.
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
}
