# Stage 1: Build and test the Java application
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# Copy the Maven configuration and source code
COPY pom.xml .
COPY src ./src

# Build the application and collect its dependencies
RUN mvn -B clean package dependency:copy-dependencies \
    -DoutputDirectory=target/dependency

# Stage 2: Create the application runtime image
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy the compiled application and required libraries
COPY --from=build /build/target/PetBoardingReservationSystem-0.0.1-SNAPSHOT.jar app.jar
COPY --from=build /build/target/dependency/ ./lib/

# Use in-memory storage for the container demonstration
ENV PET_BOARDING_STORAGE=memory

# The application provides its HTTP API on port 8082
EXPOSE 8082

# Start the Pet Boarding Reservation System
CMD ["java", "-cp", "app.jar:lib/*", "com.petboarding.Main"]