FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
RUN apk add --no-cache gradle
COPY . .
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S vastra && adduser -S vastra -G vastra
COPY --from=builder /app/build/libs/*.jar app.jar
USER vastra
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
