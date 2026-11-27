# Build image for QLogg: JDK 21, git, and Xvfb with GTK 3 and 4 for the SWT
# tests.
# The same ./mvnw runs inside as on a host. compose.yaml mounts the working
# tree at run time; nothing from it is baked in.

# Pinned by multi-platform index digest; Dependabot proposes updates.
FROM eclipse-temurin:21.0.13.1_2-jdk-noble@sha256:4dea56dcfa1a89832caf1ffe082c49b3878388df3ce97b7f0a49f9c4372b718c

# git: Spotless reads .gitattributes for line endings.
# unzip: without it ./mvnw fetches the .tar.gz Maven distribution, which fails
# the wrapper's SHA-256 pinned for the .zip.
# xvfb, xauth: xvfb-run for `check`. libgtk-3-0t64, fonts: SWT on GTK 3.
# libwebkit2gtk-4.1-0: SWT's Browser, so the user guide tests run.
# libgtk-4-1: the experimental GTK 4 run. jq: sbom-components.sh.
# x11-utils: xprop and xwininfo for the window stacking tests.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      git unzip jq xvfb xauth x11-utils libgtk-3-0t64 libgtk-4-1 \
      libwebkit2gtk-4.1-0 fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/*

# World-writable so the container can run as the caller's uid. MAVEN_USER_HOME
# holds the wrapper's Maven and the repository, .cache/qlogg-build the JDK
# archives and tools dist.xml downloads; compose.yaml backs each with a named
# volume, which takes the directory's mode when created.
ENV HOME=/opt/qlogg \
    MAVEN_USER_HOME=/opt/qlogg/.m2

RUN mkdir -p /opt/qlogg/.m2 /opt/qlogg/.cache/qlogg-build \
 && chmod -R a+rwX /opt/qlogg

WORKDIR /work
