# Java 21 container-memory behavior is part of the deployment assumptions.
# Re-verify memory behavior when changing the JRE major version.
#
# Human-readable pin: eclipse-temurin:21.0.12_8-jre-noble
FROM eclipse-temurin:21.0.12_8-jre-noble@sha256:d35199d74a3b2dff1bfb435d9adde4ef974e74a7d2a4fcb3079063c55926edb5 AS java-base

FROM java-base AS extractor

WORKDIR /workspace

COPY build/libs/app.jar application.jar

RUN java -Djarmode=tools \
    -jar application.jar \
    extract \
    --layers \
    --destination extracted

FROM java-base AS runtime

WORKDIR /application

RUN groupadd --gid 10001 triageharbor \
    && useradd \
        --uid 10001 \
        --gid 10001 \
        --no-create-home \
        --shell /usr/sbin/nologin \
        triageharbor

# Copy the least frequently changing Spring Boot layers first.
# Docker invalidates a changed layer and every following layer, so do not
# reorder these merely for cosmetic reasons.
COPY --from=extractor /workspace/extracted/dependencies/ ./
COPY --from=extractor /workspace/extracted/spring-boot-loader/ ./
COPY --from=extractor /workspace/extracted/snapshot-dependencies/ ./
COPY --from=extractor /workspace/extracted/application/ ./

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "application.jar"]
