package com.hpplay.carlife

/**
 * 需要真实网络端口的测试分组。
 *
 * [com.hpplay.carlife.transport.CarLifeWireIntegrationTest] 和
 * [com.hpplay.carlife.transport.CarLifeWireReconnectTest] 会绑定**固定**端口
 * （UDP 7999 与 7 个 CarLife 通道端口）来模拟手机端。同一台机器上并行跑它们会互相
 * 抢占端口，CI 容器里尤其容易偶发失败（表现为与代码改动无关的红灯）。
 *
 * CI 不依赖这个 Category：Gradle 的 `excludeCategories` 只对 JUnit Platform 生效，
 * 而本工程用 JUnit4，所以 CI 改用类名模式过滤（见 `carlife/build.gradle.kts` 的
 * `-PexcludeTestClasses`）。
 *
 * 保留这个 Category 是为了本地可以选择性地跑：
 *
 * ```
 * ./gradlew :carlife:testDebugUnitTest --tests '*NetworkTest*'
 * ```
 */
object NetworkTestCategory {
    /** JUnit Category 的全限定名。 */
    const val NAME = "com.hpplay.carlife.NetworkTest"
}
