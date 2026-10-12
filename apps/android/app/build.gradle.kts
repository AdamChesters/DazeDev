import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.adamchesters.dazedev.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.adamchesters.dazedev.mobile"
        minSdk = 28
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.0-alpha.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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
        resources.excludes += setOf(
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
    implementation("ai.moonshine:moonshine-voice:0.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val sharedHostAssets = tasks.register<Copy>("stageSharedHost") {
    from(rootProject.file("../desktop/src-tauri/resources/host.py"))
    into(layout.buildDirectory.dir("generated/dazedev/assets"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/dazedev/assets"))
tasks.named("preBuild").configure { dependsOn(sharedHostAssets) }

val mobileAssets = tasks.register<Exec>("stageMobileAssets") {
    inputs.file(rootProject.file("../../shared/releases/changelog.json"))
    inputs.files(rootProject.file("../../scripts/stage-android-assets.mjs"), rootProject.file("../desktop/public/brand.svg"), rootProject.file("../desktop/package-lock.json"))
    inputs.dir(rootProject.file("terminal"))
    outputs.dir(layout.buildDirectory.dir("generated/mobile"))
    commandLine("node", rootProject.file("../../scripts/stage-android-assets.mjs"), layout.buildDirectory.dir("generated/mobile").get().asFile)
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/mobile/assets"))
android.sourceSets.getByName("main").res.srcDir(layout.buildDirectory.dir("generated/mobile/res"))
tasks.named("preBuild").configure { dependsOn(mobileAssets) }

android.sourceSets.getByName("test").resources.srcDir(rootProject.file("../../shared/protocol"))

tasks.withType<Test>().configureEach {
    // Changing fixture endpoints must rerun integration tests instead of reusing cached skips.
    inputs.property("sshFixture", System.getenv("DAZEDEV_TEST_SSH_PORT") ?: "absent")
    inputs.property("realHostFixture", System.getenv("DAZEDEV_TEST_REAL_FIXTURE") ?: "absent")
}

android.sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("generated/mobile/kotlin"))
