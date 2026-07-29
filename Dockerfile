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
  && apt-get install -y --no-install-recommends temurin-11-jdk temurin-17-jdk temurin-21-jdk \
  # JDK 8 is not published for every architecture the rest of this image supports. It is
  # genuinely optional — selection drops any JDK whose path is absent — so a missing arm64
  # build must degrade to "no JDK 8 here", not fail the image for everyone.
  && (apt-get install -y --no-install-recommends temurin-8-jdk \
      || echo "WARNING: temurin-8-jdk unavailable for $(dpkg --print-architecture); repos requiring JDK 8 will not build on this arch") \
  && rm -rf /var/lib/apt/lists/*

# Temurin installs to /usr/lib/jvm/temurin-<v>-jdk-<dpkg arch>, so the real paths differ
# between amd64 and arm64 — and an ENV cannot be computed. Hardcoding the amd64 spelling
# made every JDK vanish on an arm64 host: repo.js keeps only the JAVA_HOME_<major> entries
# that fs.existsSync confirms, so the candidate list came back EMPTY and selection fell
# through to JAVA_HOME, which was equally dead. Java ran under a broken home everywhere.
#
# Stable arch-independent names fix it. A symlink to a JDK the archive does not publish for
# this architecture stays dangling, which existsSync reports as absent — exactly the
# graceful degradation the selection code already expects.
RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    mkdir -p /opt/java; \
    for v in 8 11 17 21; do ln -sfn "/usr/lib/jvm/temurin-${v}-jdk-${arch}" "/opt/java/${v}"; done; \
    ls -l /opt/java; \
    /opt/java/17/bin/java -version

ENV JAVA_HOME_8=/opt/java/8 \
    JAVA_HOME_11=/opt/java/11 \
    JAVA_HOME_17=/opt/java/17 \
    JAVA_HOME_21=/opt/java/21 \
    JAVA_HOME=/opt/java/17
ENV PATH=$JAVA_HOME/bin:$PATH

# ── Maven + Gradle ──────────────────────────────────────────────────────────
ARG MAVEN_VERSION=3.9.9
ARG GRADLE_VERSION=8.10.2
# --retry is not belt-and-braces: this step pulls two large archives from two different
# hosts, and a single transient TLS reset fails the whole image build. Observed exactly
# that on a first arm64 build — `curl: (35) SSL_ERROR_SYSCALL` — where an immediate rerun
# succeeded unchanged. Off a datacentre connection that flake is the common case, so the
# download retries rather than costing a ten-minute rebuild.
RUN curl -fsSL --retry 5 --retry-delay 3 --retry-connrefused \
      "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      | tar -xz -C /opt \
  && ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn \
  && curl -fsSL --retry 5 --retry-delay 3 --retry-connrefused \
      -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
  && unzip -q /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip \
  && ln -s /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/local/bin/gradle

# Artifact resolution goes through the host's Nexus group repo when reachable (service
# `nexus` on the mvn-cache network): warm cache, no upstream rate limits. Both files
# fall back to the public repositories when Nexus is absent.
# settings.xml ships as a TEMPLATE, not as the live file. A Maven <mirror> with
# mirrorOf=* redirects every artifact request to that URL — and when the mirror does not
# answer, Maven fails the build instead of falling back to Central. Installing it
# unconditionally therefore breaks every environment that has no Nexus, which is every
# environment except the deployment host. The entrypoint probes the mirror and installs
# this only if it actually responds. (init.gradle is safe to ship live: it probes first.)
COPY docker/settings.xml /app/docker/settings.xml.template
COPY docker/init.gradle /root/.gradle/init.gradle

ARG N8N_VERSION=2.32.0
# Same reasoning as the Maven/Gradle download above, and observed the same way: this step
# failed once with `npm error code ECONNRESET ... socket disconnected before secure TLS
# connection was established` on a build where nothing but a comment had changed. n8n is
# the largest download in the image, so it has the widest window to catch a reset.
RUN npm install -g --omit=dev n8n@${N8N_VERSION} \
      --fetch-retries=5 --fetch-retry-mintimeout=5000 --fetch-retry-maxtimeout=60000 \
  && npm cache clean --force

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
