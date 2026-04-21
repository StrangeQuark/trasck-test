FROM mcr.microsoft.com/playwright/java:v1.46.0-jammy

WORKDIR /trasck-test

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src

ENTRYPOINT ["mvn", "test"]
