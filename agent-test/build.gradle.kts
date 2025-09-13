
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

}

tasks.test {
    useJUnitPlatform()
}