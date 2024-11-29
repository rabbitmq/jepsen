# Jepsen Tests for RabbitMQ's Quorum Queue Implementation

## How to run with Docker

`cd` into the `jepsen/docker` directory and start the containers:

```shell
cd docker
ssh-keygen -t ed25519 -m pem -f shared/jepsen-bot -C jepsen-bot -N ''
docker compose up --detach
./provision.sh
```

Connect to the Jepsen control container:

```shell
docker exec -it jepsen-control bash
```

Inside the control container, `cd` into the test directory and list the test aavailable options:


```
cd /root/rabbitmq
lein run test --help
```

To run a test for 30 seconds with network partition of 10 seconds:

```
lein run test --nodes n1,n2,n3 --ssh-private-key /root/shared/jepsen-bot --time-limit 30
```

The first run can take some time because of the provisioning of the nodes.
The console output is like the following if the run is successful:

```
INFO [2024-10-21 12:48:18,043] jepsen test runner - jepsen.core {:perf {:latency-graph {:valid? true},
        :rate-graph {:valid? true},
        :valid? true},
 :queue {:ok-count 726,
         :duplicated-count 0,
         :valid? true,
         :lost-count 0,
         :lost #{},
         :acknowledged-count 725,
         :recovered #{799},
         :attempt-count 727,
         :unexpected #{},
         :unexpected-count 0,
         :recovered-count 1,
         :duplicated #{}},
 :valid? true}


Everything looks good! ヽ(‘ー`)ノ
```

Here is how to shut down and delete the containers:

```shell
docker compose down
```

## Running on a locally-built binary

The test runs by default on a RabbitMQ version available on GitHub releases.
It is also possible to run the test on a RabbitMQ Generic Unix package available on the local filesystem:

 * copy the Generic Unix archive in the `rabbitmq` directory (in the host system, not in the Docker container)
 * make sure the archive shows up in the controller Docker container: `ls -al ~/rabbitmq`
 * run the test with the `--archive-url` option, e.g.

 ```
 lein run test --nodes n1,n2,n3 --ssh-private-key /root/shared/jepsen-bot --time-limit 30 --archive-url file:///root/rabbitmq/rabbitmq-server-generic-unix-4.0.2-alpha.9.tar.xz
 ```
