import java.io.File
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** 正式签名是否就绪：环境变量给了密钥文件、且文件真的存在 */
val hasReleaseKeystore: Boolean = run {
    val path = System.getenv("KEYSTORE_FILE")
    path != null && File(path).exists()
}


android {
    namespace = "com.zhiwei.qbot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zhiwei.qbot"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "1.3.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        val ks = System.getenv("KEYSTORE_FILE")
        if (ks != null && File(ks).exists()) {
            create("release") {
                storeFile = File(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            /**
             * 正式包**只用**正式密钥签名。
             *
             * 绝不允许退回 debug 签名：一旦签名换了，用户设备上的旧版就与新版签名冲突，
             * 报「软件包与现有软件包存在冲突」，只能删掉重装并丢数据。
             * 所以这里找不到 release 签名就干脆不给签名，并在下面拦一个 Gradle 异常，
             * 让 CI 直接红掉，而不是悄悄产出一个换了签名的包。
             */
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// 正式包签名护栏：真正要出 release 包时才校验，debug 调试不受影响
gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any { it.name.contains("Release") }
    if (wantsRelease && !hasReleaseKeystore) {
        throw GradleException(
            "release 构建缺少正式签名密钥（KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD 未配置或密钥文件不存在）。" +
                "已阻止构建：换签名的包会导致旧版无法覆盖安装、用户必须卸载重装。"
        )
    }
}

// Force use of ARM64 binaries for AAPT2 in Proot environment
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
            useTarget("com.android.tools.build:aapt2:${'$'}{requested.version}:linux-aarch64")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
