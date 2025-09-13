plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "org.lawrence"
version = "1.0-SNAPSHOT"


repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    implementation("org.javassist:javassist:3.27.0-GA")
    implementation("org.slf4j:slf4j-simple:1.7.26")
    implementation("com.eclipsesource.minimal-json:minimal-json:0.9.5")
    implementation("net.bytebuddy:byte-buddy:1.14.2")

    compileOnly("org.projectlombok:lombok:1.18.22")
    annotationProcessor("org.projectlombok:lombok:1.18.22")
    compileOnly("javax.servlet:javax.servlet-api:3.0.1")
    compileOnly("jakarta.servlet:jakarta.servlet-api:5.0.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Jar> {
    manifest {
        attributes(
            "Manifest-Version" to "1.0",
            "Premain-Class" to "com.lawrence.AttachMain",
            "Main-Class" to "com.lawrence.AttachMain",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
}

// shadowJar 是 shadow 插件提供的任务
tasks.shadowJar {
    archiveClassifier.set("") // 不带 -all 后缀，生成单一可执行 jar
    mergeServiceFiles()       // 合并 META-INF/services 避免冲突
}
