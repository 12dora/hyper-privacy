import com.android.build.api.variant.ApplicationVariant

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** Generates `META-INF/xposed/scope.list` and a `<queries>` manifest from the rule catalog. */
abstract class GenerateScopeTask : JavaExec() {
    @get:OutputDirectory
    abstract val resourcesDir: DirectoryProperty

    @get:OutputFile
    abstract val queriesManifest: RegularFileProperty

    override fun exec() {
        args(
            resourcesDir.file("META-INF/xposed/scope.list").get().asFile.path,
            queriesManifest.get().asFile.path,
        )
        super.exec()
    }
}

/** Runtime classpath of :core, used only to run the scope generator at build time. */
val coreTool: Configuration = configurations.create("coreTool") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

android {
    namespace = "io.github.hyperprivacy"
    // Compose 1.13 requires compileSdk 37.1+; the runtime target stays Android 17 (API 37).
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        // Must stay under a namespace we own (GitHub org "hyprivacy") for the LSPosed module repo.
        applicationId = "io.github.hyprivacy"
        minSdk = 34
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        create("release") {
            // CI-only: values come from the environment, never from committed files.
            val keystore = System.getenv("SIGNING_KEYSTORE_PATH")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("SIGNING_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        resources.excludes += setOf("META-INF/*.version", "kotlin/**", "DebugProbesKt.bin")
        // Ships pre-stripped from AndroidX; skipping the strip step avoids requiring an NDK just to no-op.
        jniLibs.keepDebugSymbols += "**/libandroidx.graphics.path.so"
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        lintConfig = rootProject.file("config/lint/lint.xml")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val generateScope = tasks.register<GenerateScopeTask>("generateXposedScope") {
    description = "Generates the LSPosed scope list and package queries from the rule catalog."
    classpath = coreTool
    mainClass.set("io.github.hyperprivacy.core.scope.ScopeExport")
    resourcesDir.set(layout.buildDirectory.dir("generated/xposedScope/resources"))
    queriesManifest.set(layout.buildDirectory.file("generated/xposedScope/AndroidManifest.xml"))
}

androidComponents {
    onVariants { variant: ApplicationVariant ->
        variant.sources.resources?.addGeneratedSourceDirectory(generateScope, GenerateScopeTask::resourcesDir)
        variant.sources.manifests.addGeneratedManifestFile(generateScope, GenerateScopeTask::queriesManifest)
    }
}

dependencies {
    coreTool(project(":core"))
    implementation(project(":core"))

    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
