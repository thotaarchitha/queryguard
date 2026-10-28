plugins {
    `java-library`
}

// The rule model and evaluation live here so the proxy and the admin API's
// dry-run endpoint can never disagree about what a rule does.
dependencies {
    api(libs.jsqlparser)
    api(platform(libs.jackson.bom))
    api(libs.jackson.databind)
    api(libs.jackson.yaml)
    api(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.json.schema.validator)
    implementation(libs.slf4j.api)

    testRuntimeOnly(libs.slf4j.simple)
}
