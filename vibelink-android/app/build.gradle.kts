plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// 关于页上的构建编号:当前提交的短哈希;没有 git(如从源码包编译)时写 dev
val gitHash: String = project.findProperty("buildCommit")?.toString() ?: runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: "dev"

val isBetaEdition: Boolean = project.findProperty("betaEdition")?.toString()?.toBoolean() == true
// Private developer seed is supplied at build time, never from a tracked asset.
val devClashSeed = if (isBetaEdition) null else providers.gradleProperty("devClashConfig")
    .orElse(providers.environmentVariable("VIBELINK_DEV_CLASH_CONFIG")).orNull?.takeIf { it.isNotBlank() }

abstract class PrepareDevClashSeed : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val configuration: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val geoData: ConfigurableFileCollection
    @get:OutputDirectory abstract val output: DirectoryProperty
    @TaskAction fun prepare() {
        val input = configuration.get().asFile
        require(input.length() in 1..1_048_576) { "Developer Clash configuration must be nonempty and at most 1 MiB" }
        val bytes = input.readBytes()
        require(!bytes.toString(Charsets.UTF_8).contains("REPLACE_")) { "Developer Clash configuration contains placeholders" }
        output.get().asFile.resolve("private-clash/dev-seed.yaml").apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
        val dataNames = geoData.files.map { it.name }.toSet()
        val text = bytes.toString(Charsets.UTF_8)
        require(!text.contains("GEOSITE,", ignoreCase = true) || "geosite.dat" in dataNames) { "Developer seed requires bundled geosite.dat" }
        require(!text.contains("GEOIP,", ignoreCase = true) || "Country.mmdb" in dataNames || "geoip.dat" in dataNames) { "Developer seed requires bundled GeoIP data" }
        geoData.files.forEach { data ->
            output.get().asFile.resolve("private-clash/data/${data.name}").apply {
                parentFile.mkdirs()
                data.copyTo(this, overwrite = true)
            }
        }
    }
}

val prepareDevClashSeed = devClashSeed?.let { source ->
    tasks.register<PrepareDevClashSeed>("prepareDevClashSeed") {
        configuration.set(file(source))
        geoData.from(listOf("Country.mmdb", "geoip.dat", "geosite.dat").map { file(source).parentFile.resolve(it) }.filter { it.isFile })
        output.set(layout.buildDirectory.dir("generated/privateClashAssets"))
    }
}

android {
    namespace = "com.wangzan101.vibelink"
    compileSdk = 36

    defaultConfig {
        applicationId = if (isBetaEdition) "com.wangzan101.vibelink.beta" else "com.wangzan101.vibelink"
        minSdk = 30
        targetSdk = 35
        versionCode = 95
        versionName = if (isBetaEdition) "1.6.4-beta" else "1.6.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        // 关于页版本号后的 Beta 标记;出正式版时改成 false
        buildConfigField("boolean", "BETA", "true")
        // 是否为公网开放的内测用户专享版（true=剥离一切个人API/内网代理配置并锁死DeepSeek内测通道；false=本地自用开发全量版）
        buildConfigField("boolean", "IS_BETA_EDITION", if (isBetaEdition) "true" else "false")
        buildConfigField("boolean", "HAS_DEV_CLASH_SEED", if (devClashSeed != null) "true" else "false")
    }

    // 沙箱程序只有 arm64 版本(jniLibs/arm64-v8a);APK 不限 ABI,别的设备照常安装、显示「此设备不支持沙箱」。
    // 沙箱程序要从 nativeLibraryDir 直接执行,安装时必须解到磁盘
    packaging { jniLibs { useLegacyPackaging = true } }
    // 自带的运行环境(assets/runtime/rootfs.tar.xz,tools/sandbox/build-runtime.py 生成)已是 xz,不再压一遍
    androidResources { noCompress += "xz" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 评测任务集(仓库根目录 eval/tasks)打进测试包的 assets,真机评测跑器从那里读题
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("eval/tasks"))

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
        create("perf") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isProfileable = true
            matchingFallbacks += "release"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        compose = true
        dataBinding = true
        aidl = false
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation(libs.kaidl.runtime)
    ksp(libs.kaidl.compiler)
    implementation(libs.rikkax.multiprocess)
    implementation("io.github.g00fy2.quickie:quickie-bundled:1.11.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xz)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    // 两种识别模型都随 APK 安装；首次使用不依赖 Play 服务下载。
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20180813")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // 沙箱路径契约(core 的 testFixtures):AndroidSandbox 在 JVM 与真机上都跑同一套
    testImplementation(testFixtures(project(":core")))
    androidTestImplementation(testFixtures(project(":core")))

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}

/**
 * 界面文字:英文是默认(values/),中文在 values-zh/。安卓按文字体系匹配资源,values-zh 只对简体中文系统生效,
 * 繁体中文系统会落到英文;构建时把同一份中文原样复制一份给繁体(values-b+zh+Hant),所有中文系统都显示中文。
 */
abstract class HantStringsTask : DefaultTask() {
    @get:InputDirectory
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = output.get().asFile.resolve("values-b+zh+Hant")
        out.deleteRecursively()
        out.mkdirs()
        source.get().asFile.listFiles { f -> f.name.endsWith(".xml") }!!.forEach { it.copyTo(out.resolve(it.name)) }
    }
}

val hantStrings = tasks.register<HantStringsTask>("hantStrings") {
    source.set(layout.projectDirectory.dir("src/main/res/values-zh"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(hantStrings, HantStringsTask::output)
        prepareDevClashSeed?.let { variant.sources.assets?.addGeneratedSourceDirectory(it, PrepareDevClashSeed::output) }
    }
}

// LicensesTest 读仓库根目录的 THIRD_PARTY_NOTICES.md:让它参与测试的增量判断
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("THIRD_PARTY_NOTICES.md")).withPropertyName("thirdPartyNotices")
}




































