# syntax=docker/dockerfile:1

# ---- 构建阶段：maven 镜像内置 eclipse-temurin:25-jdk + Maven ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app

# 先只拷 pom 解析依赖，形成可缓存的依赖层
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

# 再拷源码打包
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- 运行阶段：仅 JRE，非 root ----
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN useradd --system --uid 1001 --shell /usr/sbin/nologin starter
COPY --from=build /app/target/auth-server-*.jar app.jar
USER starter
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
