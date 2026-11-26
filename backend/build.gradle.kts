plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.routewise"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Boot 3.4.1'in getirdiği 1.20.4, Docker Engine 29'un en düşük API sürümünün
// altında kalıyor ve "Could not find a valid Docker environment" ile düşüyor.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // JWT — api derleme zamanı, impl/jackson yalnızca çalışma zamanı
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Şema yönetimi — changelog db/changelog/db.changelog-master.yaml
    implementation("org.liquibase:liquibase-core")

    // OpenAPI / Swagger UI — /swagger-ui.html
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.3")

    // PostGIS geometri tipleri (addresses.location, depots.location)
    implementation("org.hibernate.orm:hibernate-spatial")

    runtimeOnly("org.postgresql:postgresql")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Repository testleri gerçek PostgreSQL+PostGIS ile koşar, H2 ile değil.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
    options.encoding = "UTF-8"
}
