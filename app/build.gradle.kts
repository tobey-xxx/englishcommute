import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 版本号单一真源。**只改这两行**。
 *
 * 之前 versionName 写死 0.1.0 一直没动，而我对外交付时按「第几轮修复」
 * 给 APK 文件起了 0.2.0 / 0.3.0 的名字 —— 文件名跟 Manifest 里的真实
 * 版本号是两回事，结果系统「应用信息」里永远显示 0.1.0，装完分不清是哪一版。
 *
 * ⚠️ 必须放在 plugins {} 之后：Gradle Kotlin DSL 要求 plugins 块是脚本的
 * 第一个语句，写在它前面的声明在 android {} 里会「Unresolved reference」。
 *
 * APK 文件名由 `build-apk.sh` 从**产出的 APK 里反解** versionName 后命名，
 * 并断言二者一致 —— 所以文件名和真实版本号不会再各说各话。
 */
val appVersionCode = 75
val appVersionName = "4.6.6"

android {
    namespace = "com.example.englishcommute"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.englishcommute"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        // ★ 开源版：**服务器地址不写死在仓库里**。
        //   本地构建：gradle.properties 里写 serverBaseUrl，或 -PserverBaseUrl=...
        //   生产环境建议用 HTTPS 域名（TLS + 系统 CA，不必放开明文）。
        val serverBaseUrl = (project.findProperty("serverBaseUrl") as String?)
            ?: "https://your-domain.example"
        buildConfigField("String", "SERVER_BASE_URL", "\"$serverBaseUrl\"")

        // ⚠️ 2.1.0：`ndk { abiFilters += "arm64-v8a" }` 整块删掉了。
        //
        // 它当年存在的唯一理由是 sherpa-onnx 的 AAR 内含 4 个 ABI（约 130 MB），
        // 只会跑 arm64 的机器上白背着另外三份。那个 AAR 已经随端侧识别一起拆掉了，
        // 2.1.0 的包里**一个 .so 都没有**（onnxruntime 也拆了）。
        // 留着一句 abiFilters 会让人以为还有需要裁剪的原生库。
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        // 4.6.6 起 ServerConfig.BASE_URL 从 BuildConfig 读（开源版脱敏）
        buildConfig = true
    }

    /**
     * JVM 单元测试的两个必要配置，缺任何一个都会让数据层的测试写不了。
     *
     * 1. `isReturnDefaultValues = true`：
     *    `src/test/` 跑在开发机的 JVM 上，classpath 里是 **android.jar 的空壳** ——
     *    里面每个方法体都是 `throw new RuntimeException("Stub!")`。
     *    `DiagLog` 内部调 `android.util.Log.println`，不打开这个开关的话，
     *    任何一句日志都会让测试抛异常 —— 而数据层的每一条路径都要记日志。
     *    （它只是让这些调用返回默认值、不抛，**不会**让它们真的干活。）
     *
     * 2. 下面 dependencies 里的 `org.json`：
     *    同理由，`org.json` 也是空壳。而 `WordStore` 的落盘与加载全靠它。
     */
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // 不压缩 .so：Android 15+ 要求 16 KB 页对齐，
        // 未压缩 + zipalign 才能保证 PT_LOAD p_align 生效。
        //
        // 2.1.0 起包里**没有任何 .so**（onnxruntime 与 sherpa 都拆了），
        // 但这行留着：它是一条「将来谁加了原生依赖时自动正确」的约定，
        // 而不是对当前状态的描述。`build-apk.sh` 的对齐断言同理 ——
        // 它们在「没有 .so」时会如实报「无需检查」，不会假通过。
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // ⚠️ 2.1.0：原来的 `androidResources { noCompress += listOf("onnx") }` 删掉了。
    // assets/translate 里那 108 MB 的 ONNX 模型是端侧翻译用的，
    // 已随整条链路拆掉，包里不再有任何 .onnx。
}

dependencies {
    // ⚠️ 2.1.0：原来这里有两处原生依赖，都删了 ——
    //
    //   ① `fileTree(libs, include = *.aar)`：sherpa-onnx 端侧识别运行时
    //   ② `libs.onnxruntime.android`：端侧翻译的推理运行时
    //
    // 它们让 APK 多背着两份 .so，再加上 assets 里 108 MB 的模型资产。
    // 三个一起走，APK 从 169 MB 降到二十几 MB。
    //
    // 删掉之后**没有任何 native 代码**，于是 build-apk.sh 里那几条
    // 「16 KB 页对齐 / ELF 符号版本」的断言随之进入「无需检查」状态 ——
    // 它们留着，因为那是原生依赖回来时唯一的构建期守门人。

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // 注意：不要加 compose.material-icons-extended —— 它会把 ~2000 个矢量图标编成代码，
    // 光这一项就让 debug APK 胖十几 MB。需要图标时用 material3 自带的 Icons.Default 子集。
    debugImplementation(libs.compose.ui.tooling)

    // 播放 + 字幕解析（SubripParser 在 media3-extractor 里）
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.extractor)

    implementation(libs.okhttp)
    implementation(libs.coroutines.android)

    // JVM 单元测试（`src/test/`）：跑在开发机上，**不进 APK**。
    // 见 gradle/libs.versions.toml 里关于 junit 的那段说明 ——
    // 它存在的唯一目的是给移植来的 FSRS 实现做逐值比对。
    testImplementation(libs.junit)
    // Android 的 org.json 是空壳，真实的 JSON 解析从这里来（只在测试 classpath 上）
    testImplementation(libs.json)
}
