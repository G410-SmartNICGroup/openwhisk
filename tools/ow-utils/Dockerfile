FROM adoptopenjdk/openjdk8:jdk8u262-b10

ENV DOCKER_VERSION=18.06.3-ce
ENV KUBECTL_VERSION v1.16.3
ENV WHISK_CLI_VERSION latest
ENV WHISKDEPLOY_CLI_VERSION latest

RUN apt-get update && apt-get install -y \
  git \
  jq \
  libffi-dev \
  nodejs \
  npm \
  python \
  python-pip \
  wget \
  zip \
  locales \
&& rm -rf /var/lib/apt/lists/*

# update npm
RUN npm install -g n && n stable && hash -r

RUN locale-gen en_US.UTF-8
ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8'

# Python packages
RUN pip install --upgrade pip
RUN pip install --upgrade setuptools
RUN pip install cryptography==2.5 && \
    pip install ansible==2.5.2 && \
    pip install jinja2==2.9.6 && \
    pip install docker

# Install docker client
RUN wget --no-verbose https://download.docker.com/linux/static/stable/aarch64/docker-${DOCKER_VERSION}.tgz && \
  tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker && \
  rm -f docker-${DOCKER_VERSION}.tgz && \
  chmod +x /usr/bin/docker

# Install kubectl in /usr/local/bin
RUN curl -Lo ./kubectl https://storage.googleapis.com/kubernetes-release/release/${KUBECTL_VERSION}/bin/linux/arm64/kubectl && chmod +x kubectl && mv kubectl /usr/local/bin/kubectl

# Install `wsk` cli in /usr/local/bin
RUN wget -q https://github.com/apache/openwhisk-cli/releases/download/$WHISK_CLI_VERSION/OpenWhisk_CLI-$WHISK_CLI_VERSION-linux-arm64.tgz && \
  tar xzf OpenWhisk_CLI-$WHISK_CLI_VERSION-linux-arm64.tgz -C /usr/local/bin wsk && \
  rm OpenWhisk_CLI-$WHISK_CLI_VERSION-linux-arm64.tgz

# Install wskadmin in /bin
COPY wskutil.py /bin
COPY wskprop.py /bin
COPY wskadmin /bin

# Setup tools/data for certificate generation (used by openwhisk-deploy-kube)
RUN mkdir /cert-gen
COPY openwhisk-server-key.pem /cert-gen
COPY genssl.sh /usr/local/bin/
