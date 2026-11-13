plugins {
    id("com.android.library")
}

android {
    namespace = "com.hpplay.carlife"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        // CarLifeWire 用 android.util.Log 打日志，JVM 单测里静默掉
        unitTests.isReturnDefaultValues = true
    }
}

// 需要真实网络端口的测试（绑定固定端口 7999 等）在 CI 上会互相抢端口，导致与代码
// 改动无关的偶发失败。CI 通过 -PexcludeCategories=... 把它们排除，跑确定性的逻辑测试。
tasks.withType<Test>().configureEach {
    (project.findProperty("excludeCategories") as String?)?.split(",")
        ?.filter { it.isNotBlank() }
        ?.forEach { category ->
            excludeCategories.add(category.trim())
        }
}

dependencies {
    implementation(project(":common"))

    // common 里 androidx 是 implementation，不透传，这里显式声明宿主 Activity 所需
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
