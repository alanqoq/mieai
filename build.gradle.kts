plugins {
    kotlin("jvm") version "1.9.10"
    kotlin("plugin.serialization") version "1.9.10"
    id("net.mamoe.mirai-console") version "2.16.0"
}

group = "com.mieai"
version = "1.0.4"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")
    // SQLite JDBC driver
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    // MySQL JDBC driver
    implementation("com.mysql:mysql-connector-j:8.3.0")
    // HikariCP connection pool
    implementation("com.zaxxer:HikariCP:5.1.0")
    // kotlinx coroutines (match Kotlin version)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3")
    // SnakeYAML for YAML config
    implementation("org.yaml:snakeyaml:2.2")
    compileOnly(files("/opt/overflow/content/overflow-core-all-1.1.0-all.jar"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.mieai.plugin.MieAiPlugin"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("net/mamoe/**")
        exclude("kotlin/**")
        exclude("kotlinx/**")
        exclude("META-INF/mirai-core-api.kotlin_module")
        exclude("META-INF/mirai-core.kotlin_module")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register("generatePluginServices") {
    doLast {
        val servicesDir = file("${buildDir}/resources/main/META-INF/services")
        servicesDir.mkdirs()
        // 只注册主插件（mieai-store 独立部署）
        val pluginList = listOf("com.mieai.plugin.MieAiPlugin")
        
        file("${servicesDir}/net.mamoe.mirai.console.plugin.jvm.JvmPlugin").writeText(
            pluginList.joinToString("\n")
        )
        println("[generatePluginServices] Registered plugins: $pluginList")
    }
}

tasks.named<Copy>("processResources") {
    dependsOn("compileKotlin")
    finalizedBy("generatePluginServices")
    // 强制重新生成
    outputs.upToDateWhen { false }
}
