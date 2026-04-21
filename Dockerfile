FROM mcr.microsoft.com/playwright/java:v1.46.0-jammy

WORKDIR /trascktestservice

COPY src ./src
COPY pom.xml ./

RUN apt-get update && apt-get install -y curl netcat

ENTRYPOINT ["mvn", "clean", "test"]
