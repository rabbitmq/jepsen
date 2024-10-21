# Jepsen Tests for RabbitMQ's Quorum Queue Implementation

## How to run with Docker

`cd` into the `jepsen/docker` directory and start the containers:

```shell
cd docker
ssh-keygen -t rsa -m pem -f shared/jepsen-bot -C jepsen-bot -N ''
docker compose up --detach
./provision.sh
```

Connect to the Jepsen control container:

```shell
docker exec -it jepsen-control bash
```

Inside the control container, `cd` into the test directory and list the test aavailable options:


```
cd rabbitmq
lein run test --help
```

To run a test for 30 seconds with network partition of 10 seconds:

```
lein run test --nodes n1,n2,n3 --ssh-private-key /root/shared/jepsen-bot --time-limit 30
```

The first run can take a while because of the provisioning of the nodes.
The console output is like the following if the run is successful:

```
INFO [2019-04-18 07:39:40,503] jepsen test runner - jepsen.core {:ok-count 417,
 :duplicated-count 0,
 :valid? true,
 :lost-count 0,
 :lost #{},
 :acknowledged-count 415,
 :recovered #{221 167},
 :attempt-count 417,
 :unexpected #{},
 :unexpected-count 0,
 :recovered-count 2,
 :duplicated #{}}


Everything looks good! ヽ(‘ー`)ノ
```

## Running on a locally-built binary

The test runs by default on a RabbitMQ version available on GitHub releases.
It is also possible to run the test on a RabbitMQ Generic Unix package available on the local filesystem:

 * copy the Generic Unix archive at the `rabbitmq` directory (in the host system, not in the Docker container)
 * make sure the archive shows up in the controller Docker container: `ls -al ~/rabbitmq`
 * run the test with the `--archive-url` option, e.g.

 ```
 lein run test --nodes n1,n2,n3 --ssh-private-key /root/shared/jepsen-bot --time-limit 30 --archive-url file:///root/rabbitmq/rabbitmq-server-generic-unix-4.0.2-alpha.9.tar.xz
 ```
