FROM scala
ARG OPENWHISK_JAR
ENV DOCKER_VERSION=18.06.3-ce
ENV WSK_VERSION=1.0.0
ADD bin/init /
ADD bin/stop bin/waitready /bin/
RUN chmod +x /bin/stop /bin/waitready ;\
  curl -sL \
  https://download.docker.com/linux/static/stable/aarch64/docker-${DOCKER_VERSION}.tgz \
  | tar xzvf -  -C /usr/bin --strip 1 docker/docker ;\
  curl -sL \
  https://github.com/apache/openwhisk-cli/releases/download/${WSK_VERSION}/OpenWhisk_CLI-${WSK_VERSION}-linux-arm64.tgz \
  | tar xzvf - -C /usr/bin wsk
ADD ${OPENWHISK_JAR} /openwhisk-standalone.jar
WORKDIR /
EXPOSE 8080
ENTRYPOINT  ["/init"]
