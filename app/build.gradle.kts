import com.android.build.api.artifact.SingleArtifact
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

/**
 * Fails a minified build unless R8 put the module's renamed classes into the `-repackageclasses`
 * package: stack scrubbing (ModuleFrames) recognises module frames by that prefix alone.
 */
abstract class CheckModulePackageTask : DefaultTask() {
    @get:InputFile
    abstract val mapping: RegularFileProperty

    @get:InputFile
    abstract val rules: RegularFileProperty

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun check() {
        val pkg = Regex("""^-repackageclasses '([A-Za-z_][A-Za-z0-9_]*)'""", RegexOption.MULTILINE)
            .find(rules.get().asFile.readText())?.groupValues?.get(1)
            ?: throw GradleException("proguard-rules.pro has no -repackageclasses '<package>'")
        val text = mapping.get().asFile.readText()
        for (type in listOf("ModuleFrames", "LiveStackGuard\$SingleHooker")) {
            val source = Regex.escape("io.github.hyperprivacy.xposed.$type")
            val line = Regex("^$source -> ([^:]+):$", RegexOption.MULTILINE)
                .find(text)?.groupValues?.get(1)
                ?: throw GradleException("R8 mapping lacks io.github.hyperprivacy.xposed.$type")
            if (line.substringBeforeLast('.', "") != pkg) {
                throw GradleException("$type was mapped to $line, outside the module package $pkg")
            }
        }
        stamp.get().asFile.writeText(pkg)
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
        versionCode = 5
        versionName = "0.4.1"

        // Native resolver hooks (rule native.dns): arm64-v8a only; other ABIs simply lack the
        // library and the rule reports itself as not active there.
        externalNativeBuild {
            cmake {
                abiFilters += "arm64-v8a"
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    ndkVersion = "30.0.16248370"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
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

    testOptions {
        // JVM tests of the hook layer: framework stubs answer defaults instead of throwing "Stub!".
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.useJUnitPlatform() }
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
    onVariants(selector().withBuildType("release")) { variant: ApplicationVariant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val check = tasks.register<CheckModulePackageTask>("check${name}ModulePackage") {
            description = "Checks that R8 moved module classes into the -repackageclasses package."
            mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
            rules.set(layout.projectDirectory.file("proguard-rules.pro"))
            stamp.set(layout.buildDirectory.file("intermediates/modulePackage/${variant.name}/package.txt"))
        }
        tasks.matching { it.name == "assemble$name" || it.name == "bundle$name" }.configureEach { dependsOn(check) }
    }
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

    testImplementation(libs.libxposed.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
