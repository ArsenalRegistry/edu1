FROM ktis-bastion01.container.ipc.kt.com:5000/admin/openjdk:17-alpine

ENV JAVA_OPTS="-XX:+UnlockExperimentalVMOptions -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XshowSettings:vm"
ENV JAVA_OPTS="${JAVA_OPTS} -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions -XX:InitiatingHeapOccupancyPercent=35"

ENV sourceFile build/libs/partnership-api.jar
ENV targetFile app.jar

ADD $sourceFile $targetFile
USER root

ARG ENVIRONMENT
ENV ENVIRONMENT=${ENVIRONMENT}
RUN echo "ENVIRONMENT is set to ${ENVIRONMENT}"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar $targetFile"]
