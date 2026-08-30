plugins {
    id("org.springframework.boot") version "3.5.16" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

allprojects {
    group = "uk.jtoye"
    version = "2.3.0"
}

subprojects {
    repositories {
        mavenCentral()
    }
}
