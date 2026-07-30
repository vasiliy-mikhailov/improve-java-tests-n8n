# improve-java-tests: one container running the Spring Batch orchestrator.
#
# n8n and the Node sidecar are gone — the orchestrator owns the run loop and serves the
# dashboard and API itself. The image still carries several JDKs because it BUILDS other
# people's projects: a project's build floor can exceed its declared bytecode target, and
# PIT's forked minion dies under the wrong JDK. Plus Maven and Gradle, for the same reason.
#
# The base is still a Node image, for one remaining reason: eval/*.mjs. Those three harness
# scripts are the only Node left, and until they are ported the runtime has to stay.
FROM node:22-bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
  && apt-get install -y --no-install-recommends git curl ca-certificates jq procps unzip gnupg \
  && curl -fsSL --retry 5 --retry-delay 3 --retry-connrefused https://cli.github.com/packages/githubcli-archive-keyring.gpg -o /usr/share/keyrings/githubcli-archive-keyring.gpg \
  && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" > /etc/apt/sources.list.d/github-cli.list \
  && apt-get update && apt-get install -y --no-install-recommends gh \
  && rm -rf /var/lib/apt/lists/*

# ── JDKs: Temurin 8/11/17/21 (Adoptium apt repo) ────────────────────────────
RUN curl -fsSL --retry 5 --retry-delay 3 --retry-connrefused https://packages.adoptium.net/artifactory/api/gpg/key/public -o /usr/share/keyrings/adoptium.asc \
  && echo "deb [signed-by=/usr/share/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb bookworm main" > /etc/apt/sources.list.d/adoptium.list \
  && apt-get update \
  # 25 runs the BACKEND. 11/17/21 (and 8 below) build the projects it improves — a project
  # is compiled and mutated under the JDK it actually needs, because PIT's forked minion
  # crashes under the wrong one. Two different jobs; do not collapse them into one.
  && apt-get install -y --no-install-recommends temurin-25-jdk temurin-11-jdk temurin-17-jdk temurin-21-jdk \
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
    for v in 8 11 17 21 25; do ln -sfn "/usr/lib/jvm/temurin-${v}-jdk-${arch}" "/opt/java/${v}"; done; \
    ls -l /opt/java; \
    /opt/java/17/bin/java -version

# JAVA_HOME_<major> is the set selection picks from for TARGET projects; 25 is deliberately
# not among them — no repo in the corpus targets it yet, and offering it would only add a
# wrong answer. BACKEND_JAVA_HOME is what the backend itself runs under.
ENV JAVA_HOME_8=/opt/java/8 \
    JAVA_HOME_11=/opt/java/11 \
    JAVA_HOME_17=/opt/java/17 \
    JAVA_HOME_21=/opt/java/21 \
    BACKEND_JAVA_HOME=/opt/java/25 \
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


# runtime defaults (overridable via compose env_file)
ENV DATA_DIR=/data \
    SIDECAR_PORT=3000 \
    GENERIC_TIMEZONE=UTC \
    MAVEN_OPTS="-Xmx2g" \
    GRADLE_OPTS="-Dorg.gradle.daemon=false"

# The dashboard is static HTML/CSS/JS and was never ported; it is served from DASHBOARD_DIR
# by the orchestrator, not from the jar, so it can be edited without a rebuild.
COPY dashboard /app/dashboard
COPY config /app/config
# eval harness + batch driver ship with the image; entrypoint refreshes the copy
# in /data on boot so the driver can never lag the deployed code
COPY eval /app/eval
# ── the backend ─────────────────────────────────────────────────────────────
# Built under JDK 25 (BACKEND_JAVA_HOME); the 8/11/17/21 beside it exist to build the
# projects being improved and are chosen per project.
#
# Tests RUN here rather than being skipped. They are fast (~10s) and one of them,
# TimeoutsContractTest, compares Java's compile-time constants against config/timeouts.json,
# which the JavaScript workflow generator reads. That is a contract spanning two languages
# with no compiler between them — the last time its two halves drifted, a live run hung for
# 6h47m. Better the image fails to build than that ships again.
# The root pom comes too. backend/pom.xml now inherits from it (the orchestrator needs this
# jar on its compile classpath, so both are modules of one reactor), and without ../pom.xml
# present Maven fails with "Non-resolvable parent POM" before compiling a line.
COPY pom.xml /app/pom.xml
COPY backend /app/backend
COPY orchestrator /app/orchestrator
# One reactor build: the orchestrator compiles against the backend jar, so building them
# separately would need `install` into ~/.m2 between the two.
RUN cd /app \
  && JAVA_HOME=$BACKEND_JAVA_HOME mvn -B -ntp package \
  && cp orchestrator/target/ijt-orchestrator-*.jar /app/ijt-orchestrator.jar \
  && cd /app/backend \
  # -shaded, explicitly, and NOT a glob. Since backend became a library the orchestrator
  # compiles against, shade attaches the uber-jar under a classifier instead of replacing
  # the main artifact — otherwise Jackson 2.18.2 ships unrelocated inside the jar Spring
  # Boot also puts its own managed Jackson beside, and which one wins is classpath order.
  # So target/ now holds BOTH ijt-backend-1.0.0.jar (thin, for compiling) and
  # ijt-backend-1.0.0-shaded.jar (runnable). The old `ijt-backend-*.jar` glob matched both
  # and `cp` would fail with two sources and a file destination.
  && cp target/ijt-backend-*-shaded.jar /app/ijt-backend.jar \
  && rm -rf /app/backend/target /app/orchestrator/target

COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

VOLUME /data
EXPOSE 3000
ENTRYPOINT ["/app/entrypoint.sh"]
