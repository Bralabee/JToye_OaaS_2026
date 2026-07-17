plugins {
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

allprojects {
    group = "uk.jtoye"
    version = "2.1.0"
}

subprojects {
    repositories {
        mavenCentral()
    }
}
