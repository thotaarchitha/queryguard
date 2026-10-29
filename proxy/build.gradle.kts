plugins {
    application
    alias(libs.plugins.jmh)
}

dependencies {
    implementation(project(":common"))
    implementation(libs.netty.all)
    implementation(libs.caffeine)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.micrometer.prometheus)
    implementation(libs.kafka.clients)
    implementation(libs.postgresql)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.slf4j.simple)

    jmh(libs.slf4j.simple)
}

application {
    mainClass.set("dev.queryguard.proxy.ProxyServer")
    applicationDefaultJvmArgs = listOf("-XX:+UseZGC", "-XX:+ZGenerational", "-Xss512k")
}

jmh {
    warmupIterations.set(3)
    warmup.set("3s")
    iterations.set(5)
    timeOnIteration.set("4s")
    fork.set(1)
    threads.set(1)
    resultFormat.set("JSON")
}
