package com.hpplay.carlife

/**
 * 需要真实网络端口的测试分组。
 *
 * [com.hpplay.carlife.transport.CarLifeWireIntegrationTest] 和
 * [com.hpplay.carlife.transport.CarLifeWireReconnectTest] 会绑定**固定**端口
 * （UDP 7999 与 7 个 CarLife 通道端口）来模拟手机端。同一台机器上并行跑它们会互相
 * 抢占端口，CI 容器里尤其容易偶发失败（表现为与代码改动无关的红灯）。
 *
 * 给它们打上这个 Category 后，CI 可以只跑确定性的逻辑测试：
 *
 * ```
 * ./gradlew :carlife:testDebugUnitTest -PexcludeCategories=com.hpplay.carlife.NetworkTest
 * ```
 */
object NetworkTestCategory {
    /** JUnit Category 的全限定名。 */
    const val NAME = "com.hpplay.carlife.NetworkTest"
}
