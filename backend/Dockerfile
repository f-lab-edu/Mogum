# syntax=docker/dockerfile:1
# 서버 이미지 — 로컬 compose 에서 쓰고, 운영도 같은 이미지를 쓸 수 있게 만든다(ADR-069).
# 테스트는 이미지 빌드에서 돌리지 않는다 — Testcontainers 가 Docker 를 쓰므로 호스트에서 ./gradlew test 로 돌린다.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 mogum
WORKDIR /app
COPY --from=build /workspace/build/libs/mogum.jar app.jar
USER mogum
EXPOSE 8080
# exec 형식 — java 가 PID 1 이 되어 SIGTERM 을 직접 받는다. 셸 형식이면 정상 종료 유예(04 §4.5)가 지켜지지 않는다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
