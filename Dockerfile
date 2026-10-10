FROM eclipse-temurin:21-jdk-jammy AS build
ARG LEIN_VERSION=2.13.0
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL "https://github.com/technomancy/leiningen/releases/download/${LEIN_VERSION}/leiningen-${LEIN_VERSION}-standalone.jar" -o /opt/leiningen.jar \
    && printf '%s\n' '#!/bin/sh' 'exec java -cp /opt/leiningen.jar clojure.main -m leiningen.core.main "$@"' > /usr/local/bin/lein \
    && chmod +x /usr/local/bin/lein
ENV LEIN_ROOT=1
WORKDIR /app
COPY project.clj ./
RUN lein deps

FROM build AS test
COPY resources resources
COPY src src
COPY test test
ENTRYPOINT ["lein"]
CMD ["test", ":all"]

FROM build AS package
COPY resources resources
COPY src src
RUN lein uberjar

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home bookshelf
WORKDIR /app
COPY --from=package /app/target/uberjar/bookshelf-api.jar app.jar
USER bookshelf
EXPOSE 3000
ENTRYPOINT ["java", "-jar", "app.jar"]
