FROM gradle:jdk25-alpine AS build

WORKDIR /app

COPY build.gradle settings.gradle ./
COPY gradle ./gradle

RUN gradle dependencies --no-daemon || true
COPY src ./src
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:25-jdk-alpine

WORKDIR /app

COPY --from=build /app/build/libs/app.jar app.jar

EXPOSE 7000

CMD ["java", "-jar", "app.jar"]