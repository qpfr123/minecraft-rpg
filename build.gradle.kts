plugins {
    java
}

group = "io.github.qpfr123"
version = "0.1.0-SNAPSHOT"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.49.1.0") // Paper 1.21.11에 번들된 버전과 동일
    testImplementation("com.google.code.gson:gson:2.11.0") // 리소스팩 JSON 검사(Paper에 번들된 버전과 동일)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

// 리소스팩: src/main/resourcepack → 재현 가능한 zip(같은 내용이면 같은 SHA-1) → 플러그인 jar 안 resourcepack.zip
val resourcePackZip by tasks.registering(Zip::class) {
    from("src/main/resourcepack")
    archiveFileName.set("resourcepack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/resourcepack"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.processResources {
    from(resourcePackZip)
}
