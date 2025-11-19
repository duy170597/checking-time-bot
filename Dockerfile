# Sử dụng image JDK để build
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# Copy toàn bộ project vào container
COPY . .

# Build ứng dụng (tạo jar)
RUN mvn clean package -DskipTests

# Sử dụng JDK runtime để chạy
FROM eclipse-temurin:17-jdk
WORKDIR /app

# Copy jar từ stage build sang stage run
COPY --from=build /app/target/checking-time-bot-0.0.1-SNAPSHOT.jar app.jar

# Expose port (Spring Boot mặc định 8080)
EXPOSE 8080

# Start ứng dụng
ENTRYPOINT ["java", "-jar", "app.jar"]
