# ──────────────────────────────────────────────────────────────
#  ETAPA 1 — Build con Java 25
#  gradle:jdk25-alpine incluye Gradle + JDK 25 sobre Alpine Linux
# ──────────────────────────────────────────────────────────────
FROM gradle:jdk25-alpine AS build

WORKDIR /app

# Copiar configuración primero para cachear la descarga de dependencias
COPY build.gradle settings.gradle ./
COPY gradle ./gradle

RUN gradle dependencies --no-daemon || true

# Copiar código fuente y compilar el fat JAR
COPY src ./src
RUN gradle shadowJar --no-daemon

# ──────────────────────────────────────────────────────────────
#  ETAPA 2 — Runtime con Java 25
#  eclipse-temurin:25-jdk-alpine (imagen oficial Temurin Java 25)
#  Nota: Java 25 no publica imagen -jre separada en Alpine,
#        se usa -jdk que es igualmente ligera en Alpine (~200 MB).
# ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine

WORKDIR /app

COPY --from=build /app/build/libs/app.jar app.jar

EXPOSE 7000

CMD ["java", "-jar", "app.jar"]