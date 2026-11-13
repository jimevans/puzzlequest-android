plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
}

kotlin {
    jvmToolchain(17)
}

val generatedApi = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(file("openapi.json").absolutePath)
    outputDir.set(generatedApi.get().asFile.absolutePath)
    packageName.set("com.thepuzzlequest.puzzlequest.api")
    // Start afresh each time, so a class the description no longer makes goes.
    cleanupOutput.set(true)
    library.set("jvm-okhttp4")
    configOptions.putAll(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "enumUnknownDefaultCase" to "true",
            "omitGradleWrapper" to "true",
            "omitGradlePluginVersions" to "true",
            "sourceFolder" to "src/main/kotlin",
        ),
    )
    // Milliseconds and counts are whole numbers without a format; a quest's
    // clock passes Int's limit after 24 days.
    typeMappings.put("integer", "kotlin.Long")
    // A free-form value (a quest event's data) is any JSON.
    typeMappings.put("AnyType", "JsonElement")
    importMappings.put("JsonElement", "kotlinx.serialization.json.JsonElement")
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    generateApiTests.set(false)
    generateModelTests.set(false)
}

sourceSets.main {
    kotlin.srcDir(generatedApi.map { it.dir("src/main/kotlin") })
}

tasks.compileKotlin {
    dependsOn(tasks.openApiGenerate)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Part of its API: PuzzleQuestApi takes and shares an OkHttpClient.
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// Contract tests: the client against a real server (scripts/start-test-server.sh),
// named by the environment variables it writes to .test-server/env. Without
// them, they're skipped.
testing {
    suites {
        register<JvmTestSuite>("contractTest") {
            useJUnit(libs.versions.junit)
            dependencies {
                implementation(project())
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.okhttp)
            }
            targets.all {
                testTask.configure {
                    systemProperty("pq.pinnedDescription", rootProject.file("api/openapi.json").absolutePath)
                    // The server's state changes as they run; never reuse a result.
                    outputs.upToDateWhen { false }
                }
            }
        }
    }
}
