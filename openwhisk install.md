# Install k8s

## Prerequisites

​	需要确保已经安装好docker，并且服务器之间连接畅通。同时，防火墙已经关闭。

+ docker安装：`curl -fsSL "https://get.docker.com/" | sh`

## Install Kubernetes and tools

​	安装k8s及其工具。

```
curl -s https://packages.cloud.google.com/apt/doc/apt-key.gpg | sudo apt-key add
echo "deb https://apt.kubernetes.io/ kubernetes-xenial main" >> ~/kubernetes.list
sudo mv ~/kubernetes.list /etc/apt/sources.list.d
sudo apt update
sudo apt-get install -y kubelet=1.20.5-00 kubeadm=1.20.5-00 kubectl=1.20.5-00 kubernetes-cni
```

## Disabling Swap Memory

​	禁止交换memory。

```
sudo swapoff -a
```

## Set hostname

​	需要对`/etc/hosts`与`/etc/hostname`进行编辑，包含所有节点的名字与ip。若`/etc/hosts`修改无效，可以使用`sudo hostnamectl set-hostname ...`来替换。

## Letting Iptables See Bridged Traffic

+ 编辑/lib/systemd/system/docker.service，在ExecStart=..上面加入：`ExecStartPost=/sbin/iptables -I FORWARD -s 0.0.0.0/0 -j ACCEPT`

+ 设定/etc/sysctl.d/k8s.conf的系统参数：

  ```
  cat <<EOF > /etc/sysctl.d/k8s.conf
  net.ipv4.ip_forward = 1
  net.bridge.bridge-nf-call-ip6tables = 1
  net.bridge.bridge-nf-call-iptables = 1
  EOF
  
  sysctl -p /etc/sysctl.d/k8s.conf
  ```

## Changing Docker Cgroup Driver

​	配置docker。

```
sudo mkdir /etc/docker
cat <<EOF | sudo tee /etc/docker/daemon.json
{ "exec-opts": ["native.cgroupdriver=systemd"],
"log-driver": "json-file",
"log-opts":
{ "max-size": "100m" },
"storage-driver": "overlay2"
}
EOF

sudo systemctl enable docker
sudo systemctl daemon-reload
sudo systemctl restart docker
```

## Initializing the Kubernetes Master Node

​	在主节点上执行如下操作：

```
sudo kubeadm init --pod-network-cidr=10.244.0.0/16 --apiserver-advertise-address=192.168.122.1

  mkdir -p $HOME/.kube
  sudo cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
  sudo chown $(id -u):$(id -g) $HOME/.kube/config

export KUBECONFIG=/etc/kubernetes/admin.conf
```

注意，`--apiserver-advertise-address`后面跟着你想要设置的master ip地址。此时，会出现类似下面的信息：

```
root@k8s-master:~# sudo kubeadm init --pod-network-cidr=10.244.0.0/16 --apiserver-advertise-address=192.168.122.1
[init] Using Kubernetes version: v1.25.2
[preflight] Running pre-flight checks
[preflight] Pulling images required for setting up a Kubernetes cluster
[preflight] This might take a minute or two, depending on the speed of your internet connection
[preflight] You can also perform this action in beforehand using 'kubeadm config images pull'
[certs] Using certificateDir folder "/etc/kubernetes/pki"
[certs] Generating "ca" certificate and key
[certs] Generating "apiserver" certificate and key
[certs] apiserver serving cert is signed for DNS names [k8s-master kubernetes kubernetes.default kubernetes.default.svc kubernetes.default.svc.cluster.local] and IPs [10.96.0.1 192.168.122.1]
[certs] Generating "apiserver-kubelet-client" certificate and key
[certs] Generating "front-proxy-ca" certificate and key
[certs] Generating "front-proxy-client" certificate and key
[certs] Generating "etcd/ca" certificate and key
[certs] Generating "etcd/server" certificate and key
[certs] etcd/server serving cert is signed for DNS names [k8s-master localhost] and IPs [192.168.122.1 127.0.0.1 ::1]
[certs] Generating "etcd/peer" certificate and key
[certs] etcd/peer serving cert is signed for DNS names [k8s-master localhost] and IPs [192.168.122.1 127.0.0.1 ::1]
[certs] Generating "etcd/healthcheck-client" certificate and key
[certs] Generating "apiserver-etcd-client" certificate and key
[certs] Generating "sa" key and public key
[kubeconfig] Using kubeconfig folder "/etc/kubernetes"
[kubeconfig] Writing "admin.conf" kubeconfig file
[kubeconfig] Writing "kubelet.conf" kubeconfig file
[kubeconfig] Writing "controller-manager.conf" kubeconfig file
[kubeconfig] Writing "scheduler.conf" kubeconfig file
[kubelet-start] Writing kubelet environment file with flags to file "/var/lib/kubelet/kubeadm-flags.env"
[kubelet-start] Writing kubelet configuration to file "/var/lib/kubelet/config.yaml"
[kubelet-start] Starting the kubelet
[control-plane] Using manifest folder "/etc/kubernetes/manifests"
[control-plane] Creating static Pod manifest for "kube-apiserver"
[control-plane] Creating static Pod manifest for "kube-controller-manager"
[control-plane] Creating static Pod manifest for "kube-scheduler"
[etcd] Creating static Pod manifest for local etcd in "/etc/kubernetes/manifests"
[wait-control-plane] Waiting for the kubelet to boot up the control plane as static Pods from directory "/etc/kubernetes/manifests". This can take up to 4m0s
[apiclient] All control plane components are healthy after 20.506385 seconds
[upload-config] Storing the configuration used in ConfigMap "kubeadm-config" in the "kube-system" Namespace
[kubelet] Creating a ConfigMap "kubelet-config" in namespace kube-system with the configuration for the kubelets in the cluster
[upload-certs] Skipping phase. Please see --upload-certs
[mark-control-plane] Marking the node k8s-master as control-plane by adding the labels: [node-role.kubernetes.io/control-plane node.kubernetes.io/exclude-from-external-load-balancers]
[mark-control-plane] Marking the node k8s-master as control-plane by adding the taints [node-role.kubernetes.io/control-plane:NoSchedule]
[bootstrap-token] Using token: hdtdef.al4h3ohmyy54zy9b
[bootstrap-token] Configuring bootstrap tokens, cluster-info ConfigMap, RBAC Roles
[bootstrap-token] Configured RBAC rules to allow Node Bootstrap tokens to get nodes
[bootstrap-token] Configured RBAC rules to allow Node Bootstrap tokens to post CSRs in order for nodes to get long term certificate credentials
[bootstrap-token] Configured RBAC rules to allow the csrapprover controller automatically approve CSRs from a Node Bootstrap Token
[bootstrap-token] Configured RBAC rules to allow certificate rotation for all node client certificates in the cluster
[bootstrap-token] Creating the "cluster-info" ConfigMap in the "kube-public" namespace
[kubelet-finalize] Updating "/etc/kubernetes/kubelet.conf" to point to a rotatable kubelet client certificate and key
[addons] Applied essential addon: CoreDNS
[addons] Applied essential addon: kube-proxy

Your Kubernetes control-plane has initialized successfully!

To start using your cluster, you need to run the following as a regular user:

  mkdir -p $HOME/.kube
  sudo cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
  sudo chown $(id -u):$(id -g) $HOME/.kube/config

Alternatively, if you are the root user, you can run:

  export KUBECONFIG=/etc/kubernetes/admin.conf

You should now deploy a pod network to the cluster.
Run "kubectl apply -f [podnetwork].yaml" with one of the options listed at:
  https://kubernetes.io/docs/concepts/cluster-administration/addons/

Then you can join any number of worker nodes by running the following on each as root:

kubeadm join 192.168.122.1:6443 --token fsba4z.qyq654qfa2yev1dw \
        --discovery-token-ca-cert-hash sha256:b0da7126e5b87a391d137eba5e80d912efe32fe8e1d20b73c880b923149f8f8a 
```

最后一行的join需要在slave节点上使用。

​	若出现如下错误：

```
invalid or incomplete external CA: failure loading key for apiserver: couldn't load the private key file /etc/kubernetes/pki/apiserver.key: open /etc/kubernetes/pki/apiserver.key: no such file or directory
```

则先参考https://blog.csdn.net/m0_57053326/article/details/120136621，使用kubeadm reset再init。在需要重新配置时，也需要先kubeadm reset。

​	若出现unhealthy错误，则需要采用以下命令：

```
sudo swapoff -a
sudo sed -i '/ swap / s/^/#/' /etc/fstab
```



## Deploying a Pod Network

​	在每个节点上执行如下操作，以让防火墙通过：

```
sudo ufw allow 6443
sudo ufw allow 6443/tcp
```

​	之后，在master节点上执行如下操作：

```
wget https://docs.projectcalico.org/v3.20/manifests/calico.yaml
kubectl apply -f calico.yaml
```

​	此时，调用`kubectl get pods --all-namespaces`，能看到所有`Running`。若出现`0/1 RUNNING`，可以参考https://blog.csdn.net/MrFDd/article/details/123358476，https://github.com/projectcalico/calico/issues/2042#issuecomment-408488357进行添加。注意要添加真实的网卡。而如果出现其他情况，可以`kubectl describe pods -A`查看日志，看pods有什么问题。

​	`kubectl get componentstatus`的所有message应该都是ok，status为healthy。出现unhealthy见https://www.cloudsigma.com/how-to-install-and-use-kubernetes-on-ubuntu-20-04/。

## Joining Worker Nodes to the Kubernetes Cluster

​	使用上一步骤得到的join命令，在slave节点运行。若出现如下报错：

```
Status from runtime service failed" err="rpc error: code = Unimplemented desc = unknown service runtime.v1alpha2.RuntimeService
```

参照https://blog.csdn.net/m0_61237221/article/details/125223937，使用如下命令即可解决：

```
mv /etc/containerd/config.toml /tmp/
systemctl restart containerd
```

​	最后，在master上使用`kubectl get nodes`，能看到status都是READY。若不是则需要检查是否有步骤未完成，否则建议上网查。

# Install openwhisk

## Prerequisites

​	Dynamic Volume Provisioning：这里以NFS为例，见https://github.com/apache/openwhisk-deploy-kube/blob/master/docs/k8s-nfs-dynamic-storage.md

```
sudo apt install nfs-kernel-server
sudo mkdir /var/nfs/kubedata -p
sudo chown nobody: /var/nfs/kubedata
sudo systemctl enable nfs-server.service
sudo systemctl start nfs-server.service
```

​	然后修改`/etc/exports`，加入`/var/nfs/kubedata *(rw,sync,no_subtree_check,no_root_squash,no_all_squash)`，再执行`sudo exportfs -rav`。

​	最后按照上述链接建立四个yaml文件，再一一apply即可。

​	出现`MountVolume.SetUp failed for volume "nfs-client-root" : mount failed: exit status 32`，需要在所有node上安装`nfs-common`。

​	这个或许有用https://github.com/justmeandopensource/kubernetes/issues/52。

## Install Helm

```
curl https://baltocdn.com/helm/signing.asc | gpg --dearmor | sudo tee /usr/share/keyrings/helm.gpg > /dev/null
sudo apt-get install apt-transport-https --yes
echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/helm.gpg] https://baltocdn.com/helm/stable/debian/ all main" | sudo tee /etc/apt/sources.list.d/helm-stable-debian.list
sudo apt-get update
sudo apt-get install helm
```

## MyCluster.yaml

```
whisk:
  ingress:
    type: NodePort
    apiHostName: 192.168.122.1
    apiHostPort: 31001
    useInternally: false

nginx:
  httpsNodePort: 31001

# disable affinity
affinity:
  enabled: false
toleration:
  enabled: false
invoker:
  options: "-Dwhisk.kubernetes.user-pod-node-affinity.enabled=false"
  # must use KCF as kind uses containerd as its container runtime
  containerFactory:
    impl: "kubernetes"
```

​	注意把localhost换成主机的host，比如192.168.122.1。注意，最后`disablt affinity`适用于单worker节点，将来可能需要改动。

​	注意，在Dynamic Volume Provisioning未完成时，需要根据https://github.com/apache/openwhisk-deploy-kube/blob/master/docs/configurationChoices.md#persistence加入如下东西：

```
k8s:
  persistence:
    enabled: false
```



## Deploying Released Charts from Helm Repository

```
helm repo add openwhisk https://openwhisk.apache.org/charts
helm repo update
helm install owdev openwhisk/openwhisk -n openwhisk --create-namespace -f mycluster.yaml
```

​	同时，可以使用`kubectl get pods -n openwhisk --watch`观察openwhisk的pods状态。

## Install wsk cli

```
wget https://github.com/apache/openwhisk-cli/releases/download/1.2.0/OpenWhisk_CLI-1.2.0-linux-arm64.tgz
tar zxvf OpenWhisk_CLI-1.2.0-linux-arm64.tgz 
rm OpenWhisk_CLI-1.2.0-linux-arm64.tgz 
rm LICENSE.txt
rm READMD.md
rm NOTICE.txt
chmod 777 wsk
mv wsk /usr/bin
```

## Configure the wsk CLI

```
wsk property set --apihost 192.168.122.1:31001
wsk property set --auth 23bc46b1-71f6-4ed5-8c54-816aa4f8c502:123zO3xZCLrMN6v2BKK1dXYFpXlPkccOFqm12CdAsMgRU4VrNZ9lyGVCGuMDGIwP
```

​	注意把apihost后面的ip改成master的ip。

# ~~Install CouchDB~~ 不需要安装

全程可参照https://docs.couchdb.org/en/3.2.2-docs/install/unix.html进行操作。

+ 参照https://github.com/apache/couchdb/blob/main/INSTALL.Unix.md安装dependencies

+ erlang需要 >= 22，参照https://www.erlang-solutions.com/downloads/安装

+ 需要安装npm，`sudo apt-get install npm`

+ 需要安装python3venv，`sudo apt install python3.8-venv`

+ 编辑`src/couch/rebar.config.script`中的`SMVsn`，去除其判断，将其改为安装的`libmozjs`版本。例如，若安装了`libmozjs-68-dev`，则`SMVsn="68"`

+ 按照教程，`./configure, make release`即可

+ 创建超级用户：

  ```
  adduser --system \
          --home /home/couchdb \
          --no-create-home \
          --shell /bin/bash \
          --group --gecos \
          "CouchDB Administrator" couchdb
  ```

+ 创建用户，这里以openwhisk为名字，123456为密码

  ```
  curl -X PUT http://localhost:5984/_users/org.couchdb.user:jan \
       -H "Accept: application/json" \
       -H "Content-Type: application/json" \
       -d '{"name": "openwhisk", "password": "123456", "roles": [], "type": "user"}'
  ```

+ 再按照https://docs.couchdb.org/en/3.2.2-docs/install/unix.html进行后续操作即可。

# Deploying OpenWhisk using Ansible

### Getting started

If you want to deploy OpenWhisk locally using Ansible, you first need to install Ansible on your development environment:

#### Ubuntu users

```
sudo apt-get install python-pip
sudo pip install ansible==4.1.0
sudo pip install jinja2==3.0.1
```

### Using Ansible

**Caveat:** All Ansible commands are meant to be executed from the `ansible` directory. This is important because that's where `ansible.cfg` is located which contains generic settings that are needed for the remaining steps.

Set the environment for the commands below by running. 这句话每次换终端都需要重新跑

```
ENVIRONMENT=local  # or docker-machine or jenkins or vagrant
```

The default environment is `local` which works for Ubuntu and Docker for Mac. To use the default environment, you may omit the `-i` parameter entirely. For older Mac installation using Docker Machine, use `-i environments/docker-machine`.

In all instructions, replace `<openwhisk_home>` with the base directory of your OpenWhisk source tree. e.g. `openwhisk`

#### Ansible with pyenv (local dev only)

When using [pyenv](https://github.com/pyenv/pyenv) to manage your versions of python, the [ansible python interpreter](https://docs.ansible.com/ansible/latest/reference_appendices/python_3_support.html) will use your system's default python, which may have a different version.

To make sure ansible uses the same version of python which you configured, execute:

```
echo -e "\nansible_python_interpreter: `which python`\n" >> ./environments/local/group_vars/all
```

#### Preserving configuration and log directories on reboot

```
export OPENWHISK_TMP_DIR=/home/huazhang/openwhisk_tmp_dir
```

When using the local Ansible environment, configuration and log data is stored in `/tmp` by default. However, operating system such as Linux and Mac clean the `/tmp` directory on reboot, resulting in failures when OpenWhisk tries to start up again. To avoid this problem, export the `OPENWHISK_TMP_DIR` variable assigning it the path to a persistent directory before deploying OpenWhisk.

#### Setup

This step should be executed once per development environment. It will generate the `hosts` configuration file based on your environment settings.

> This file is generated automatically for an ephemeral CouchDB instance during `setup.yml`.

The default configuration does not run multiple instances of core components (e.g., controller, invoker, kafka). You may elect to enable high-availability (HA) mode by passing tne Ansible option `-e mode=HA` when executing this playbook. This will configure your deployment with multiple instances (e.g., two Kafka instances, and two invokers).

In addition to the host file generation, you need to configure the database for your deployment. This is done by modifying the file `ansible/db_local.ini` to provide the following properties.

```
[db_creds]
db_provider=
db_username=
db_password=
db_protocol=
db_host=
db_port=
```

For convenience, you can use shell environment variables that are read by the playbook to generate the required `db_local.ini` file as shown below.

```
export OW_DB=CouchDB
export OW_DB_USERNAME=admin
export OW_DB_PASSWORD=123456
export OW_DB_PROTOCOL=http
export OW_DB_HOST=172.17.0.1
export OW_DB_PORT=5984

ansible-playbook -i environments/$ENVIRONMENT setup.yml
```

#### Install Prerequisites

> This step is not required for local environments since all prerequisites are already installed, and therefore may be skipped.

This step needs to be done only once per target environment. It will install necessary prerequisites on all target hosts in the environment.

```
ansible-playbook -i environments/$ENVIRONMENT prereq.yml
```

**Hint:** During playbook execution the `TASK [prereq : check for pip]` can show as failed. This is normal if no pip is installed. The playbook will then move on and install pip on the target machines.

### Deploying Using CouchDB

- Make sure your `db_local.ini` file is [setup for](https://github.com/apache/openwhisk/blob/master/ansible/README.md#setup) CouchDB then execute:

```
cd <openwhisk_home>
./gradlew distDocker
cd ansible
ansible-playbook -i environments/$ENVIRONMENT couchdb.yml
ansible-playbook -i environments/$ENVIRONMENT initdb.yml
ansible-playbook -i environments/$ENVIRONMENT wipe.yml
ansible-playbook -i environments/$ENVIRONMENT openwhisk.yml

# installs a catalog of public packages and actions
ansible-playbook -i environments/$ENVIRONMENT postdeploy.yml

# to use the API gateway
ansible-playbook -i environments/$ENVIRONMENT apigateway.yml
ansible-playbook -i environments/$ENVIRONMENT routemgmt.yml
```

- 若`./gradlew distDocker`报错

  - 报`Dockerfile`有错，则先找到能执行的Docker version(例如`ENV DOCKER_VERSION=18.06.3-ce`)，把ENV里面的换成这个，然后再手动改指令换成当前平台能跑的指令
  - 出现`protoc-3.4.0_linux-aarch_64.exe`，则参照https://github.com/apache/openwhisk/issues/5196修改成3.5.0
  - pull access denied for scala, repository does not exist or may require 'docker login': denied: requested access to the resource is denied，参考https://github.com/apache/openwhisk/issues/5159。再不行就在docker build … -t …的-t后面加上`--no-cache`。docker版本最好升级到最新版本，旧版本有问题
  - linux/arm64/v8没有zookeeper：修改roles/zookerper/tasks/deploy.yml文件的image，直接修改成存在的对应版本。原有版本是3.4.0，不存在linux/arm64/v8版本
  - FAILED - RETRYING: wait until the Zookeeper in this host is up and running ：https://github.com/apache/openwhisk/commit/7a283a0a71f20e9658d06b14d13d06add99360ea

- `ansible-playbook -i environments/$ENVIRONMENT openwhisk.yml`需要进行如下修改：

  - `/home/huazhang/openwhisk/ansible/conf`下新建`zoo.cfg`，内容：

    ```
    dataDir=/data
    dataLogDir=/datalog
    tickTime=2000
    initLimit=5
    syncLimit=2
    autopurge.snapRetainCount=3
    autopurge.purgeInterval=0
    maxClientCnxns=60
    standaloneEnabled=true
    admin.enableServer=true
    4lw.commands.whitelist=*
    clientPort=2181    
    ```
    
  - `/home/huazhang/openwhisk/ansible/roles/zookeeper/tasks/deploy.yml`改成下面这个：

    ```
    #
    # Licensed to the Apache Software Foundation (ASF) under one or more
    # contributor license agreements.  See the NOTICE file distributed with
    # this work for additional information regarding copyright ownership.
    # The ASF licenses this file to You under the Apache License, Version 2.0
    # (the "License"); you may not use this file except in compliance with
    # the License.  You may obtain a copy of the License at
    #
    #     http://www.apache.org/licenses/LICENSE-2.0
    #
    # Unless required by applicable law or agreed to in writing, software
    # distributed under the License is distributed on an "AS IS" BASIS,
    # WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    # See the License for the specific language governing permissions and
    # limitations under the License.
    #
    ---
    # This role will install Kafka with Zookeeper in group 'kafka' in the environment inventory
    
    
    - name: (re)start zookeeper
      docker_container:
        name: zookeeper{{ groups['zookeepers'].index(inventory_hostname) }}
        image: zookeeper:latest
        state: started
        recreate: true
        restart_policy: "{{ docker.restart.policy }}"
        env:
            TZ: "{{ docker.timezone }}"
            ZOO_MY_ID: "{{ groups['zookeepers'].index(inventory_hostname) + 1 }}"
        volumes:
          - "/home/huazhang/openwhisk/ansible/conf:/conf"
        ports:
          - "{{ zookeeper.port + groups['zookeepers'].index(inventory_hostname) }}:2181"
          - "{{ 2888 + groups['zookeepers'].index(inventory_hostname) }}:2888"
          - "{{ 3888 + groups['zookeepers'].index(inventory_hostname) }}:3888"
    
    - name: wait until the Zookeeper in this host is up and running
      action: shell (echo ruok; sleep 1) | nc -w 3 -i 3 {{ ansible_host }} {{ zookeeper.port + groups['zookeepers'].index(inventory_hostname) }}
      register: result
      until: (result.rc == 0) and (result.stdout == 'imok')
      retries: 32
      delay: 5
    ```

    最大区别是删掉了`zoo_servers`部分，以及挂载`/conf`文件

  - kafka镜像换成`wurstmeister/kafka:latest`

  - 在`/home/owuser`下找不到验证文件：修改`roles/controller/tasks/deploy.yml`，加入`/home/owuser`对`/home/owuser`的映射。invoker同理。

  - 手动跑zookeeper：

    ```
    docker run -e TZ="UTC" -p 2181:2181 -v "/home/huazhang/openwhisk/ansible/conf:/conf" --name zookeeper0 --restart always zookeeper:latest
    ```

  - 删除了`/roles/cli-install/tasks/deploy.yml`，因为wsk已经安装了

- Error starting userland proxy: listen tcp 0.0.0.0:6379: bind: address already in use：https://blog.csdn.net/qq_17623363/article/details/106436394

- 手动跑api-gateway：

  ```
  docker run -e PUBLIC_MANAGEDURL_HOST=172.17.0.1 -e PUBLIC_MANAGEDURL_PORT=9001 -e REDIS_HOST=172.17.0.1 -e REDIS_PASS=openwhisk -e REDIS_PORT=6379 -e TZ="UTC" -p 9001:8080 -p 9000:9000 --name apigateway --restart always openwhisk/apigateway:latest
  ```

+ apigateway.yml对于arm64平台需要手动安装，见官网https://github.com/apache/openwhisk-apigateway/
  + ftp.pcre.org不存在：https://www.pcre.org/
  + 需要把Dockerfile中加的aarch64补丁全删去，即https://github.com/apache/openwhisk-apigateway/pull/285的94\~102行
  + 然后把`roles/apigateway/tasks/deploy.yml`的pull删去，把image改成latest

- rm有可能没有释放空间：https://m.php.cn/article/491987.html。以及，docker可能存在大量缓存，可以参考https://moxiao.blog.csdn.net/article/details/84404519删去
- 完全清除docker：https://stackoverflow.com/questions/46672001/is-it-safe-to-clean-docker-overlay2
- You need to run `initdb.yml` **every time** you do a fresh deploy CouchDB to initialize the subjects database.
- The `wipe.yml` playbook should be run on a fresh deployment only, otherwise actions and activations will be lost.
- Run `postdeploy.yml` after deployment to install a catalog of useful packages.
- To use the API Gateway, you'll need to run `apigateway.yml` and `routemgmt.yml`.
- Use `ansible-playbook -i environments/$ENVIRONMENT openwhisk.yml` to avoid wiping the data store. This is useful to start OpenWhisk after restarting your Operating System.

#### Limitation

You cannot run multiple CouchDB nodes on a single machine. This limitation comes from Erlang EPMD which CouchDB relies on to find other nodes. To deploy multiple CouchDB nodes, they should be placed on different machines respectively otherwise their ports will clash.

### Configuring the installation of `wsk` CLI

There are two installation modes to install `wsk` CLI: remote and local.

The mode "remote" means to download the `wsk` binaries from available web links. By default, OpenWhisk sets the installation mode to remote and downloads the binaries from the CLI [release page](https://github.com/apache/openwhisk-cli/releases), where OpenWhisk publishes the official `wsk` binaries.

The mode "local" means to build and install the `wsk` binaries from local CLI project. You can download the source code of OpenWhisk CLI [here](https://github.com/apache/openwhisk-cli). Let's assume your OpenWhisk CLI home directory is `$OPENWHISK_HOME/../openwhisk-cli` and you've already `export`ed `OPENWHISK_HOME` to be the root directory of this project. After you download the CLI repository, use the gradle command to build the binaries (you can omit the `-PnativeBuild` if you want to cross-compile for all supported platforms):

```
cd "$OPENWHISK_HOME/../openwhisk-cli"
./gradlew releaseBinaries -PnativeBuild
```

The binaries are generated and put into a tarball in the folder `../openwhisk-cli/release`. Then, use the following Ansible command to (re-)configure the CLI installation:

```
export OPENWHISK_ENVIRONMENT=local  # ... or whatever
ansible-playbook -i environments/$OPENWHISK_ENVIRONMENT edge.yml -e mode=clean
ansible-playbook -i environments/$OPENWHISK_ENVIRONMENT edge.yml \
    -e cli_installation_mode=local \
    -e openwhisk_cli_home="$OPENWHISK_HOME/../openwhisk-cli"
```

The parameter `cli_installation_mode` specifies the CLI installation mode and the parameter `openwhisk_cli_home` specifies the home directory of your local OpenWhisk CLI. (*n.b.* `openwhisk_cli_home` defaults to `$OPENWHISK_HOME/../openwhisk-cli`.)

Once the CLI is installed, you can [use it to work with Whisk](https://github.com/apache/openwhisk/blob/master/docs/cli.md).

## Test

+ 按这个进行安装：https://github.com/apache/openwhisk-runtime-nodejs

+ container：一定要暴露出来端口！

  ```
  docker run -d -p 8080:8080 whisk/action-nodejs-v14
  ```

+ hello.js

  ```
  function main(params) {
      var name = params.name || 'World';
      return {payload:  'Hello, ' + name + '!'};
  }
  ```

+ `wsk action create hellotest hello.js -i`：ok: created action hellotest

+ `wsk action invoke hellotest --result -i`：

  ```reStructuredText
  {
      "playload": "hello world"
  }
  ```

  看到此结果即为成功。不成功可以利用`-d`看debug信息。

+ 如果出现x509: cannot validate certificate for 172.17.0.1 because it doesn't contain any IP SAN，则需要加上`-i`以未验证身份的形式操作即可。

+ 如果test的container不符合预期：

  ```
  wsk action update hellotest hello.js --docker whisk/action-nodejs-v14 -i
  ```

## 一些文件

+ common/scala/Dockerfile：

  ```
  FROM adoptopenjdk/openjdk11-openj9:aarch64-ubuntu-jdk-11.0.8_10_openj9-0.21.0
  
  ENV LANG en_US.UTF-8
  ENV LANGUAGE en_US:en
  ENV LC_ALL en_US.UTF-8
  
  # Switch to the HTTPS endpoint for the apk repositories as per https://github.com/gliderlabs/docker-alpine/issues/184
  # RUN sed -i 's/http\:\/\/dl-cdn.alpinelinux.org/https\:\/\/alpine.global.ssl.fastly.net/g' /etc/apk/repositories
  # RUN apk add --update sed curl bash && apk update && apk upgrade
  
  RUN mkdir /logs
  
  COPY transformEnvironment.sh /
  RUN chmod +x transformEnvironment.sh
  
  COPY copyJMXFiles.sh /
  RUN chmod +x copyJMXFiles.sh
  ```

+ tools/ow-utils/Dockerfile

  ```
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
  ```

+ tools/actionProxy/Dockerfile

  ```
  openwhisk/dockerskeleton
  ```

+ core/scheduler/Dockerfile

  ```
  FROM scala
  
  ENV UID=1001 \
      NOT_ROOT_USER=owuser
  
  # Copy app jars
  ADD build/distributions/scheduler.tar /
  
  COPY init.sh /
  RUN chmod +x init.sh
  
  RUN adduser --disabled-passowrd --uid ${UID} --home /home/${NOT_ROOT_USER} --shell /bin/bash ${NOT_ROOT_USER}
  USER ${NOT_ROOT_USER}
  
  EXPOSE 8080
  CMD ["./init.sh", "0"]      
  ```

+ core/standalone/Dockerfile

  ```
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
  ```

+ core/cosmosdb/cache-invalidator/Dockerfile

  ```
  FROM scala
  
  ENV UID=1001 \
      NOT_ROOT_USER=owuser
  
  
  # Copy app jars
  ADD build/distributions/cache-invalidator.tar /
  
  COPY init.sh /
  RUN chmod +x init.sh
  
  RUN adduser --disabled-passowrd --uid ${UID} --home /home/${NOT_ROOT_USER} --shell /bin/bash ${NOT_ROOT_USER}
  USER ${NOT_ROOT_USER}
  
  EXPOSE 8080
  CMD ["./init.sh", "0"]
  ```

+ core/monitoring/user-events/Dockerfile

  ```
  FROM scala
  
  ENV UID=1001 \
      NOT_ROOT_USER=owuser
  
  # Copy app jars
  ADD build/distributions/user-events.tar /
  
  COPY init.sh /
  RUN chmod +x init.sh
  
  RUN adduser --disabled-passowrd --uid ${UID} --home /home/${NOT_ROOT_USER} --shell /bin/bash ${NOT_ROOT_USER}
  USER ${NOT_ROOT_USER}
  
  # Prometheus port
  EXPOSE 9095
  CMD ["./init.sh", "0"]
  ```

+ core/invoker/Dockerfile

  ```
  FROM scala
  
  ENV UID=1001 \
      NOT_ROOT_USER=owuser \
      DOCKER_VERSION=18.06.3-ce
  # If you change the docker version here, it has implications on invoker runc support.
  # Docker server version and the invoker docker version must be the same to enable runc usage.
  # If this cannot be guaranteed, set `invoker_use_runc: false` in the ansible env.
  
  
  # RUN apk add --update openssl
  
  # Uncomment to fetch latest version of docker instead: RUN wget -qO- https://get.docker.com | sh
  # Install docker client
  RUN curl -sSL -o docker-${DOCKER_VERSION}.tgz https://download.docker.com/linux/static/stable/aarch64/docker-${DOCKER_VERSION}.tgz && \
      tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker && \
      tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker-runc && \
      rm -f docker-${DOCKER_VERSION}.tgz && \
      chmod +x /usr/bin/docker && \
      chmod +x /usr/bin/docker-runc
  
  ADD build/distributions/invoker.tar ./
  
  COPY init.sh /
  RUN chmod +x init.sh
  
  # When running the invoker as a non-root user this has implications on the standard directory where runc stores its data.
  # The non-root user should have access on the directory and corresponding permission to make changes on it.
  RUN adduser --disabled-passowrd --uid ${UID} --home /home/${NOT_ROOT_USER} --shell /bin/bash ${NOT_ROOT_USER}
  
  EXPOSE 8080
  CMD ["./init.sh", "0"]
  ```

+ core/controller/Dockerfile

  ```
  FROM scala
  
  ENV UID=1001 \
      NOT_ROOT_USER=owuser
  ENV SWAGGER_UI_DOWNLOAD_SHA256=3d7ef5ddc59e10f132fe99771498f0f1ba7a2cbfb9585f9863d4191a574c96e7 \
      SWAGGER_UI_VERSION=3.6.0
  
  ENV DOCKER_VERSION=18.06.3-ce
  
  # RUN apk add --update openssl
  
  # Uncomment to fetch latest version of docker instead: RUN wget -qO- https://get.docker.com | sh
  # Install docker client
  RUN curl -sSL -o docker-${DOCKER_VERSION}.tgz https://download.docker.com/linux/static/stable/aarch64/docker-${DOCKER_VERSION}.tgz && \
      tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker && \
      tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker-runc && \
      rm -f docker-${DOCKER_VERSION}.tgz && \
      chmod +x /usr/bin/docker && \
      chmod +x /usr/bin/docker-runc
  ##################################################################################################
  
  # Install swagger-ui
  RUN curl -sSL -o swagger-ui-v${SWAGGER_UI_VERSION}.tar.gz --no-verbose https://github.com/swagger-api/swagger-ui/archive/v${SWAGGER_UI_VERSION}.tar.gz && \
      echo "${SWAGGER_UI_DOWNLOAD_SHA256}  swagger-ui-v${SWAGGER_UI_VERSION}.tar.gz" | sha256sum -c - && \
      mkdir swagger-ui && \
      tar zxf swagger-ui-v${SWAGGER_UI_VERSION}.tar.gz -C /swagger-ui --strip-components=2 swagger-ui-${SWAGGER_UI_VERSION}/dist && \
      rm swagger-ui-v${SWAGGER_UI_VERSION}.tar.gz && \
      sed -i s#http://petstore.swagger.io/v2/swagger.json#/api/v1/api-docs#g /swagger-ui/index.html
  
  # Copy app jars
  ADD build/distributions/controller.tar /
  
  COPY init.sh /
  RUN chmod +x init.sh
  
  RUN adduser --disabled-passowrd --uid ${UID} --home /home/${NOT_ROOT_USER} --shell /bin/bash ${NOT_ROOT_USER}
  # It is possible to run as non root if you dont need invoker capabilities out of the controller today
  # When running it as a non-root user this has implications on the standard directory where runc stores its data.
  # The non-root user should have access on the directory and corresponding permission to make changes on it.
  #USER ${NOT_ROOT_USER}
  
  EXPOSE 8080
  CMD ["./init.sh", "0"]
  ```