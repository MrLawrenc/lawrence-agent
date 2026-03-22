
plugins {
    id("java")
    id("org.springframework.boot") version "3.1.2"
}

group = "org.lawrence"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    implementation("org.javassist:javassist:3.27.0-GA")
    implementation("net.bytebuddy:byte-buddy:1.14.2")
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter
    implementation("org.springframework.boot:spring-boot-starter:3.1.2")

    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-web
    implementation("org.springframework.boot:spring-boot-starter-web:3.1.2")

    implementation("org.springframework.boot:spring-boot-starter-jdbc:3.1.2")
    runtimeOnly("com.h2database:h2:2.2.224")
    runtimeOnly("com.mysql:mysql-connector-j:8.0.33")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    val agentJar = "${project.rootDir}/../javassist-agent/build/libs/javassist-agent-1.0-SNAPSHOT.jar"
    jvmArgs("-javaagent:$agentJar=agent.properties")
}