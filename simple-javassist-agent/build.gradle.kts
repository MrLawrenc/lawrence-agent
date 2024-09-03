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

}

tasks.jar {
    manifest {
        val mainClass = "com.lawrence.Javassist02WriteJavaAgent"
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
