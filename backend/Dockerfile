# Spring Boot backend image: build with the Maven wrapper, run on a slim JRE.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Windows checkouts can leave CRLF line endings in the wrapper script.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:17-jre
# The backend finds the worker folder next to itself (../Data_Quality_Wevioo), so the compose file
# mounts it at /workspace/Data_Quality_Wevioo and the app runs from /workspace/backend.
WORKDIR /workspace/backend
COPY --from=build /build/target/backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
