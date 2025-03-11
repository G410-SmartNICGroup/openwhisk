# 准备：通过docker安装Elasticsearch

在部署OpenWhisk之前，我们需要先安装其依赖的数据库服务：couchDB和Elasticsearch。最简单的方式是通过Docker来部署这两个服务。

## Tip：CouchDB安装由后续ansible脚本自动安装

后续安装openWhisk过程中用到的脚本会自动帮我们安装。

如果想自己部署，参考CouchDB在GitHub上提供的官方镜像：https://github.com/apache/couchdb-docker

## Elasticsearch安装并在单节点集群上部署

1. 拉取Elasticsearch镜像：
```bash
docker pull docker.elastic.co/elasticsearch/elasticsearch-wolfi:8.17.3
```

2. 创建容器网络：
```bash
docker network create elastic
```

3. 创建并启动Elasticsearch容器：
```bash
docker run --name es01 \
    --net elastic \
    -p 9200:9200 \
    -it -m 1GB docker.elastic.co/elasticsearch/elasticsearch:8.17.3
```

4. 从运行镜像时的输出中得到访问密码并设置为环境变量

```bash
export ELASTIC_PASSWORD="your_password"
```

5. 获得访问所需的SSL签名证书：

```bash
sudo docker cp es01:/usr/share/elasticsearch/config/certs/http_ca.crt /root
```


6. 验证Elasticsearch是否正常运行：
```bash
curl --cacert /root/http_ca.crt -u elastic:$ELASTIC_PASSWORD https://localhost:9200
```

这两个服务启动后，我们就可以继续进行OpenWhisk的安装了。

# 使用Ansible安装OpenWhisk

## 安装Ansible

### 前置条件：python3和pip3

### 安装命令：

```bash
sudo apt-get install python-pip
sudo pip install ansible==4.1.0
sudo pip install jinja2==3.0.1
```

### 设置本地环境文件夹

通过下列命令指明配置文件夹为`$OW_HOME/ansible/environments/local`

```bash
ENVIRONMENT=local
```

### 设置CouchDB环境

```bash
export OW_DB=CouchDB
export OW_DB_USERNAME=admin
export OW_DB_PASSWORD=couchDB123456
export OW_DB_PROTOCOL=htpp
export OW_DB_HOST=127.0.0.1
export OW_DB_PORT=5984

ansible-playbook -i environments/$ENVIRONMENT setup.yml
```

### 开始通过ansible安装OW

上面的每一步配置好后，开始通过ansible脚本进行部署
```bash
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
如果遇到报错，日志在/var/tmp/wsklogs文件夹下面，可以查看具体报错原因

# 分布式部署OpenWhisk
分布式部署中，我们采用一台主机作为集群的Master控制节点，负责运行集群的控制组件；
另一台主机作为Slave节点，负责运行Invoker容器镜像。
如无特殊说明，下面的命令都是在openwhisk/ansible文件夹中执行。
## 创建新的hosts配置文件夹
复制原来的loca文件夹为distributed文件夹
```bash
cp environments/local/ environments/distributed/
```

修改distributed文件夹下的hosts文件如下：
```
; the first parameter in a host is the inventory_hostname

; used for local actions only
192.168.35.5  ansible_connection=local ansible_user=huazhang
192.168.35.8 ansible_connection=ssh ansible_user=huazhang
ansible ansible_connection=local

[edge]
192.168.35.5          ansible_host=192.168.35.5 ansible_connection=local

[controllers]
controller0         ansible_host=192.168.35.5 ansible_connection=local
;{% if mode is defined and 'HA' in mode %}
;controller1         ansible_host=192.168.35.5 ansible_connection=local
;{% endif %}

[kafkas]
192.168.35.5              ansible_host=192.168.35.5 ansible_connection=local

[zookeepers:children]
kafkas

[invokers]
192.168.35.8            ansible_host=192.168.35.8 ansible_connection=ssh

[schedulers]
192.168.35.5       ansible_host=192.168.35.5 ansible_connection=local

; db group is only used if db.provider is CouchDB
[db]
192.168.35.5          ansible_host=192.168.35.5 ansible_connection=local

[elasticsearch:children]
db

[redis]
192.168.35.5          ansible_host=192.168.35.5 ansible_connection=local

[apigateway]
192.168.35.5          ansible_host=192.168.35.5 ansible_connection=local

[etcd]
192.168.35.5            ansible_host=192.168.35.5 ansible_connection=local
```
其中192.168.35.5是Master的IP地址，192.168.35.8是Invoker所在主机的地址
192.168.35.8需要通过ssh来连接，后续我们的命令都是root用户权限执行，所以需要把`/root/.ssh/`目录下
的ssh公钥加到192.168.35.8主机`~/.ssh/authorized_keys`文件中；
同时Master ssh到slave后是普通用户身份，需要获得root权限才能继续执行docker相关命令，因此我们需要
修改ansible.cfg文件，添加以下配置：
```
# 普通用户提权配置
[privilege_escalation] 
# sudo时是否提示输入密码
become_ask_pass=False
# 以sudo方式提权
become_method=sudo 
# 默认sudo到root
become_user=root
become                  = true
default_become          = true
```

接下来需要配置存储集群元数据的数据库，我们使用couchdb，与local不同的是，这次我们需要配置一个slave可访问的IP地址，
具体配置如下：（从这里开始使用root用户进行配置）
```bash
export OW_DB=CouchDB
export OW_DB_USERNAME=admin
export OW_DB_PASSWORD=couchdb123456
export OW_DB_PROTOCOL=http
export OW_DB_HOST=192.168.35.5
export OW_DB_PORT=5984
ansible-playbook -i environments/$ENVIRONMENT setup.yml
```
之后检查db_local.ini配置是否符合，检查environments/$ENVIRONMENT/group_vars/all中elasticsearch相关配置是否配置好。

查看Master能否ping通slave并检验slave相关环境是否配置好：
```bash
ansible-playbook -i environments/$ENVIRONMENT prereq.yml
```
如果这里出错，检查前面提到的各种配置是否正确

接下来需要配置一下openwhisk目录下的whisk.properties，不清楚这个是自动生成的还是手动配置的，打开看一下是不是我们想要的：
```
python.27=python
nginx.conf.dir=/var/tmp/wskconf/nginx
testing.auth=/home/huazhang/zyq/openwhisk/ansible/../ansible/files/auth.guest
vcap.services.file=

whisk.logs.dir=/var/tmp/wsklogs
whisk.coverage.logs.dir=/var/tmp/wskcov
environment.type=local
whisk.ssl.client.verification=off
whisk.ssl.cert=/home/huazhang/zyq/openwhisk/ansible/roles/nginx/files/openwhisk-server-cert.pem
whisk.ssl.key=/home/huazhang/zyq/openwhisk/ansible/roles/nginx/files/openwhisk-server-key.pem
whisk.ssl.challenge=openwhisk

whisk.api.host.proto=https
whisk.api.host.port=443
whisk.api.host.name=192.168.35.5
whisk.api.localhost.name=localhost
whisk.api.vanity.subdomain.parts=1

whisk.action.concurrency=True
whisk.feature.requireApiKeyAnnotation=true
whisk.feature.requireResponsePayload=true

runtimes.manifest={"description": ["This file describes the different languages (aka. managed action runtimes) supported by the system", "as well as blackbox images that support the runtime specification.", "Only actions with runtime families / kinds defined here can be created / read / updated / deleted / invoked.", "Define a list of runtime families (example: 'nodejs') with at least one kind per family (example: 'nodejs:20').", "Each runtime family needs a default kind (default: true).", "When removing or renaming runtime families or runtime kinds from this file, preexisting actions", "with the affected kinds can no longer be read / updated / deleted / invoked. In order to remove or rename", "runtime families or runtime kinds, mark all affected runtime kinds as deprecated (deprecated: true) and", "perform a manual migration of all affected actions.", "", "This file is meant to list all stable runtimes supported by the Apache Openwhisk community."], "runtimes": {"nodejs": [{"kind": "nodejs:18", "default": false, "image": {"prefix": "openwhisk", "name": "action-nodejs-v18", "tag": "nightly"}, "deprecated": false, "attached": {"attachmentName": "codefile", "attachmentType": "text/plain"}}, {"kind": "nodejs:20", "default": true, "image": {"prefix": "openwhisk", "name": "action-nodejs-v20", "tag": "nightly"}, "deprecated": false, "attached": {"attachmentName": "codefile", "attachmentType": "text/plain"}, "stemCells": [{"initialCount": 2, "memory": "256 MB", "reactive": {"minCount": 1, "maxCount": 4, "ttl": "2 minutes", "threshold": 1, "increment": 1}}]}], "python": [{"kind": "python:3.10", "default": true, "image": {"prefix": "openwhisk", "name": "action-python-v3.10", "tag": "nightly"}, "deprecated": false, "attached": {"attachmentName": "codefile", "attachmentType": "text/plain"}}, {"kind": "python:3.11", "default": false, "image": {"prefix": "openwhisk", "name": "action-python-v3.11", "tag": "nightly"}, "deprecated": false, "attached": {"attachmentName": "codefile", "attachmentType": "text/plain"}}], "swift": [{"kind": "swift:5.3", "default": true, "image": {"prefix": "openwhisk", "name": "action-swift-v5.3", "tag": "nightly"}, "deprecated": false, "attached": {"attachmentName": "codefile", "attachmentType": "text/plain"}}, {"kind": "swift:5.7", "default": false, "image": {"prefix": "openwhisk", "name": "action-swift-v5.7", "tag": "nightly"}, "deprecated": false, "attached": {"attachmentName": "codefile", "attachmentType": "text/plain"}}], "java": [{"kind": "java:8", "default": true, "image": {"prefix": "openwhisk", "name": "java8action", "tag": "nightly"}, "deprecated": false, "attached": {"attachmentName": "codefile", "attachmentType": "text/plain"}, "requireMain": true}], "php": [{"kind": "php:8.1", "default": true, "deprecated": false, "image": {"prefix": "openwhisk", "name": "action-php-v8.1", "tag": "nightly"}, "attached": {"attachmentName": "codefile", "attachmentType": "text/plain"}}], "ruby": [{"kind": "ruby:2.5", "default": true, "deprecated": false, "attached": {"attachmentName": "codefile", "attachmentType": "text/plain"}, "image": {"prefix": "openwhisk", "name": "action-ruby-v2.5", "tag": "nightly"}}], "go": [{"kind": "go:1.20", "default": true, "deprecated": false, "attached": {"attachmentName": "codefile", "attachmentType": "text/plain"}, "image": {"prefix": "openwhisk", "name": "action-golang-v1.20", "tag": "nightly"}}], "dotnet": [{"kind": "dotnet:3.1", "default": true, "deprecated": false, "requireMain": true, "image": {"prefix": "openwhisk", "name": "action-dotnet-v3.1", "tag": "nightly"}, "attached": {"attachmentName": "codefile", "attachmentType": "text/plain"}}, {"kind": "dotnet:6.0", "default": false, "deprecated": false, "requireMain": true, "image": {"prefix": "openwhisk", "name": "action-dotnet-v6.0", "tag": "nightly"}, "attached": {"attachmentName": "codefile", "attachmentType": "text/plain"}}], "rust": [{"kind": "rust:1.34", "default": true, "image": {"prefix": "openwhisk", "name": "action-rust-v1.34", "tag": "nightly"}, "deprecated": false, "attached": {"attachmentName": "codefile", "attachmentType": "text/plain"}}]}, "blackboxes": [{"prefix": "openwhisk", "name": "dockerskeleton", "tag": "nightly"}]}

limits.actions.invokes.perMinute=60
limits.actions.invokes.concurrent=30
limits.triggers.fires.perMinute=60
limits.actions.sequence.maxLength=50

edge.host=192.168.35.5
kafka.hosts=192.168.35.5:9093
redis.host=192.168.35.5
router.host=192.168.35.5
zookeeper.hosts=192.168.35.5:2181
invoker.hosts=192.168.35.5

edge.host.apiport=443
kafkaras.host.port=8093
redis.host.port=6379
invoker.hosts.basePort=12001
invoker.username=invoker.user
invoker.password=invoker.pass

controller.hosts=192.168.35.5
controller.host.basePort=10001
controller.instances=1
controller.protocol=https
controller.username=controller.user
controller.password=controller.pass

invoker.container.network=bridge
invoker.container.policy=
invoker.container.dns=
invoker.useRunc=True

main.docker.endpoint=192.168.35.5:4243

docker.registry=
docker.image.prefix=whisk
#use.docker.registry=false
docker.port=4243
docker.timezone.mount=
docker.image.tag=latest
docker.tls.cmd=
docker.addHost.cmd=
docker.dns.cmd=
docker.restart.opts=always

db.provider=CouchDB
db.protocol=http
db.host=192.168.35.5
db.port=5984
db.username=admin
db.password=couchDB123456
db.prefix=whisk_local_
db.whisk.auths=whisk_local_subjects
db.whisk.actions=whisk_local_whisks
db.whisk.activations=whisk_local_activations
db.hostsList=192.168.35.5
db.instances=1

apigw.auth.user=
apigw.auth.pwd=
apigw.host.v2=http://192.168.35.5:9000/v2
```

比较关键的是这里db的配置，需要被Invoker访问到。

这时候Master上的配置就结束了，建议查看一下slave上面的docker是否启动，最好重启一下，然后确保docker可以正常拉去镜像。

接下来开始启动集群，首先退出到上级openwhisk目录
```bash
cd ..
./gradlew distDocker
cd ansible
ansible-playbook -i environments/$ENVIRONMENT couchdb.yml
ansible-playbook -i environments/$ENVIRONMENT initdb.yml
ansible-playbook -i environments/$ENVIRONMENT wipe.yml
ansible-playbook -i environments/$ENVIRONMENT openwhisk.yml
ansible-playbook -i environments/$ENVIRONMENT apigateway.yml
```