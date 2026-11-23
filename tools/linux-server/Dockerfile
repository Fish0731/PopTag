FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build/server
COPY server/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY server/src ./src
RUN mvn -q -B -DskipTests compile dependency:copy-dependencies -DoutputDirectory=target/lib

FROM eclipse-temurin:21-jre
WORKDIR /app/server
COPY --from=builder /build/server/target/classes ./classes
COPY --from=builder /build/server/target/lib ./lib
COPY server/Config.yaml server/*.yaml ./
COPY tools/linux-server/poptag.sh /app/poptag.sh
RUN chmod +x /app/poptag.sh && mkdir -p /app/logs /app/server/data/local-accounts

EXPOSE 3838/tcp 4848/tcp 4849/tcp 47611/tcp
EXPOSE 3839/udp 4849/udp 4151-4160/udp

ENTRYPOINT ["/app/poptag.sh", "fg"]
