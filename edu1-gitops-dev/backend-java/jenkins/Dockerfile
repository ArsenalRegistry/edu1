# Start with a base image containing Java runtime
FROM openjdk:17-alpine

# Set the working directory inside the container
WORKDIR /app

# Copy the JAR file from the build context into the container
COPY build/libs/backend-java-*.jar app.jar

# Specify the command to run the JAR file
CMD ["java", "-jar", "app.jar"]
