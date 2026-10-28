plugins {
    application
}

dependencies {
    implementation(project(":common"))
    implementation(libs.netty.all)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

application {
    mainClass.set("dev.queryguard.proxy.ProxyServer")
}
