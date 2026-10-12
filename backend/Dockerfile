# Çok aşamalı derleme: derleyici katmanı imajda kalmaz.
FROM gradle:8.12-jdk21 AS build
WORKDIR /src

# Bağımlılıklar önce indirilsin ki kaynak değişince katman yeniden kullanılsın.
COPY settings.gradle.kts build.gradle.kts ./
RUN gradle --no-daemon dependencies --quiet || true

COPY src ./src
RUN gradle --no-daemon bootJar --quiet

FROM eclipse-temurin:21-jre
WORKDIR /srv
COPY --from=build /src/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/srv/app.jar"]
