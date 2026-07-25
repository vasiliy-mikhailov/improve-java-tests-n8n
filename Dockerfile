# improve-java-tests-n8n: n8n orchestrator + sidecar runner + dashboard, one container.
# The sidecar drives real Java tooling, so the image carries several JDKs (a project's
# build floor can exceed its declared bytecode target, and PIT's forked minion dies
# under the wrong JDK), plus Maven and Gradle.
FROM node:22-bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
  && apt-get install -y --no-install-recommends git curl ca-certificates jq procps unzip gnupg \
  && curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg -o /usr/share/keyrings/githubcli-archive-keyring.gpg \
  && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" > /etc/apt/sources.list.d/github-cli.list \
  && apt-get update && apt-get install -y --no-install-recommends gh \
  && rm -rf /var/lib/apt/lists/*

# ── JDKs: Temurin 8/11/17/21 (Adoptium apt repo) ────────────────────────────
RUN curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public -o /usr/share/keyrings/adoptium.asc \
  && echo "deb [signed-by=/usr/share/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb bookworm main" > /etc/apt/sources.list.d/adoptium.list \
  && apt-get update \
  && apt-get install -y --no-install-recommends temurin-8-jdk temurin-11-jdk temurin-17-jdk temurin-21-jdk \
  && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME_8=/usr/lib/jvm/temurin-8-jdk-amd64 \
    JAVA_HOME_11=/usr/lib/jvm/temurin-11-jdk-amd64 \
    JAVA_HOME_17=/usr/lib/jvm/temurin-17-jdk-amd64 \
    JAVA_HOME_21=/usr/lib/jvm/temurin-21-jdk-amd64 \
    JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

# ── Maven + Gradle ──────────────────────────────────────────────────────────
ARG MAVEN_VERSION=3.9.9
ARG GRADLE_VERSION=8.10.2
RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      | tar -xz -C /opt \
  && ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn \
  && curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
  && unzip -q /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip \
  && ln -s /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/local/bin/gradle

# Artifact resolution goes through the host's Nexus group repo when reachable (service
# `nexus` on the mvn-cache network): warm cache, no upstream rate limits. Both files
# fall back to the public repositories when Nexus is absent.
COPY docker/settings.xml /root/.m2/settings.xml
COPY docker/init.gradle /root/.gradle/init.gradle

ARG N8N_VERSION=2.32.0
RUN npm install -g --omit=dev n8n@${N8N_VERSION} && npm cache clean --force

# n8n + runtime defaults (overridable via compose env_file)
ENV DATA_DIR=/data \
    N8N_USER_FOLDER=/data \
    N8N_PORT=5678 \
    SIDECAR_PORT=3000 \
    N8N_USER_MANAGEMENT_JWT_DURATION_HOURS=87600 \
    N8N_SECURE_COOKIE=false \
    N8N_PROXY_HOPS=1 \
    N8N_DIAGNOSTICS_ENABLED=false \
    N8N_PERSONALIZATION_ENABLED=false \
    N8N_VERSION_NOTIFICATIONS_ENABLED=false \
    N8N_TEMPLATES_ENABLED=false \
    N8N_HIRING_BANNER_ENABLED=false \
    EXECUTIONS_TIMEOUT=-1 \
    N8N_RUNNERS_TASK_TIMEOUT=7200 \
    GENERIC_TIMEZONE=UTC \
    MAVEN_OPTS="-Xmx2g" \
    GRADLE_OPTS="-Dorg.gradle.daemon=false"

COPY sidecar /app/sidecar
COPY n8n /app/n8n
# eval harness + batch driver ship with the image; entrypoint refreshes the copy
# in /data on boot so the driver can never lag the deployed code
COPY eval /app/eval
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh && node /app/n8n/generate-workflows.mjs

VOLUME /data
EXPOSE 5678 3000
ENTRYPOINT ["/app/entrypoint.sh"]
