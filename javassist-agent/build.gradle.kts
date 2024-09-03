import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Date

plugins {
    id("java")
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

    compileOnly("org.projectlombok:lombok:1.18.22")
    annotationProcessor("org.projectlombok:lombok:1.18.22")
    compileOnly("javax.servlet:javax.servlet-api:3.0.1")
    compileOnly("jakarta.servlet:jakarta.servlet-api:5.0.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        val mainClass = "com.lawrence.AttachMain"
        val suffix = mainClass.substring(mainClass.lastIndexOf("."))
        //定义包名
        val archivesBaseName = "${project.name}"
        archiveVersion = "${archiveVersion.getOrNull()}_${suffix}"
        archiveClassifier = "BETA"
        attributes["Manifest-Version"] = 1.0
        attributes["Can-Redefine-Classes"] = true
        attributes["Can-Retransform-Classes"] = true
        attributes["Premain-Class"] = mainClass
    }
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })
    val sourcesMain = sourceSets.main.get()
    sourcesMain.allSource.forEach { println("add from sources: ${it.name}") }
    from(sourcesMain.output)
}