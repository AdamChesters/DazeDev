import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

ktlint {
    version.set("1.8.0")
    // Generated sources are not ours to format.
    filter { exclude { it.file.path.contains("/build/generated/") } }
}

android {
    namespace = "com.adamchesters.dazedev.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.adamchesters.dazedev.mobile"
        minSdk = 28
        targetSdk = 36
        versionCode = 8
        versionName = "0.1.0-alpha.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes only from the environment or Gradle properties; nothing is committed.
    val releaseSigning =
        listOf(
            "DAZEDEV_RELEASE_STORE_FILE",
            "DAZEDEV_RELEASE_STORE_PASSWORD",
            "DAZEDEV_RELEASE_KEY_ALIAS",
            "DAZEDEV_RELEASE_KEY_PASSWORD",
        ).associateWith {
            providers
                .environmentVariable(it)
                .orElse(providers.gradleProperty(it))
                .orNull
                ?.takeIf(String::isNotBlank)
        }
    signingConfigs {
        if (releaseSigning.values.all { it != null }) {
            create("release") {
                storeFile = file(releaseSigning.getValue("DAZEDEV_RELEASE_STORE_FILE")!!)
                storePassword = releaseSigning.getValue("DAZEDEV_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigning.getValue("DAZEDEV_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("DAZEDEV_RELEASE_KEY_PASSWORD")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes +=
            setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.github.mwiede:jsch:2.28.7")
    // JSch on Android needs Bouncy Castle for Ed25519, X25519 and ChaCha20 on every API level.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("androidx.webkit:webkit:1.17.1")
    implementation("ai.moonshine:moonshine-voice:0.1.5")

    testImplementation("junit:junit:4.13.2")
    // Android's own org.json implementation (AOSP libcore), so JVM tests parse and print JSON as the phone does.
    testImplementation("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val sharedHostAssets =
    tasks.register<Copy>("stageSharedHost") {
        from(rootProject.file("../desktop/src-tauri/resources/host.py"))
        from(rootProject.file("../desktop/src-tauri/resources/browser.py"))
        into(layout.buildDirectory.dir("generated/dazedev/assets"))
    }
android.sourceSets
    .getByName("main")
    .assets
    .srcDir(layout.buildDirectory.dir("generated/dazedev/assets"))
tasks.named("preBuild").configure { dependsOn(sharedHostAssets) }

val mobileAssets =
    tasks.register<Exec>("stageMobileAssets") {
        inputs.file(rootProject.file("../../shared/releases/changelog.json"))
        inputs.files(rootProject.file("../../scripts/stage-android-assets.mjs"), rootProject.file("../desktop/public/brand.svg"), rootProject.file("../desktop/package-lock.json"))
        inputs.dir(rootProject.file("terminal"))
        outputs.dir(layout.buildDirectory.dir("generated/mobile"))
        commandLine(
            "node",
            rootProject.file("../../scripts/stage-android-assets.mjs"),
            layout.buildDirectory
                .dir("generated/mobile")
                .get()
                .asFile,
        )
    }
android.sourceSets
    .getByName("main")
    .assets
    .srcDir(layout.buildDirectory.dir("generated/mobile/assets"))
android.sourceSets
    .getByName("main")
    .res
    .srcDir(layout.buildDirectory.dir("generated/mobile/res"))
tasks.named("preBuild").configure { dependsOn(mobileAssets) }

android.sourceSets
    .getByName("test")
    .resources
    .srcDir(rootProject.file("../../shared/protocol"))

tasks.withType<Test>().configureEach {
    // Changing fixture endpoints must rerun integration tests instead of reusing cached skips.
    inputs.property("sshFixture", System.getenv("DAZEDEV_TEST_SSH_PORT") ?: "absent")
    inputs.property("realHostFixture", System.getenv("DAZEDEV_TEST_REAL_FIXTURE") ?: "absent")
}

// Release APKs ship 64-bit ARM only; debug builds keep every ABI for emulators.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes
            .addAll(listOf("lib/armeabi-v7a/**", "lib/x86/**", "lib/x86_64/**"))
    }
}

// Never fall back to an unsigned or debug-signed release.
tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") {
        doFirst {
            if (android.signingConfigs.findByName("release") == null) {
                throw GradleException(
                    "Release signing is not configured. Set DAZEDEV_RELEASE_STORE_FILE, DAZEDEV_RELEASE_STORE_PASSWORD, " +
                        "DAZEDEV_RELEASE_KEY_ALIAS and DAZEDEV_RELEASE_KEY_PASSWORD as environment variables or Gradle properties.",
                )
            }
        }
    }
}

android.sourceSets
    .getByName("main")
    .java
    .srcDir(layout.buildDirectory.dir("generated/mobile/kotlin"))

// The generated Kotlin folder is part of the main source set, so ktlint must run after it is staged.
tasks.matching { it.name.startsWith("runKtlint") }.configureEach { dependsOn(mobileAssets) }
