# ধাপ ১: Maven দিয়ে বানানো (dependency আলাদা স্তরে, যাতে কোড বদলালে আবার নামাতে না হয়)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ধাপ ২: ছোট রানটাইম ইমেজ
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
