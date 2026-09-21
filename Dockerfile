# See ci-build-publish.yml which sets baseImage=hmcts/apm-services:25-jre and agentDemand:ubuntu-j25
# azure pipeline replaces $BASE_IMAGE with crmdvrepo01.azurecr.io + $baseImage
# This image has the hmcts self signing certificate authority added to truststore so we dont need to worry about about the certs
# If pulling this locally we need to authenticate to acr ... az login; az acr login -n crmdvrepo01
ARG BASE_IMAGE
# Fallback pinned to digest so the tag can't be silently repointed upstream.
# The default is only used for a local build; ADO substitutes the ACR base. Kept on the same
# Ubuntu release as that base (24.04) so a local image resembles the deployed one, and so the
# Trivy scan in ci-build-publish.yml measures something that reaches an environment.
FROM ${BASE_IMAGE:-eclipse-temurin:25-jre-noble@sha256:b4c93a50fc67612798db73d68ca3b0ee4ebdd51736e59cca370e689b9797037e}

# install curl for debugging
RUN apt-get update \
    && apt-get install -y curl \
    && rm -rf /var/lib/apt/lists/*

# run as non-root ... group and user "app"
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app

# ---- Application files ----
COPY docker/* /app/
COPY build/libs/*.jar /app/
COPY lib/applicationinsights.json /app/

USER app
ENTRYPOINT ["/bin/sh","./startup.sh"]