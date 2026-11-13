# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy wrapper + pom first — Docker layer cache avoids re-downloading deps on
# every code change (only invalidated when pom.xml changes).
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml ./
RUN ./mvnw dependency:go-offline -B

# Copy source and build
COPY src src
RUN ./mvnw clean package -DskipTests -B

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Pin the in-cluster Keycloak self-signed certificate (ADDS to the default cacerts; never trust-all).
# Keep the PEM in the image so KeycloakTrustAnchorLogger can report it at startup.
COPY cert/self-signed/finmates.cert /opt/finmates/finmates.cert
RUN keytool -importcert -noprompt -trustcacerts -alias finmates-keycloak -cacerts -storepass changeit -file /opt/finmates/finmates.cert

COPY --from=builder /build/target/*.jar app.jar


ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
