# Jepsen Tests for RabbitMQ


## How to run

From the root directory of the project:

```
export JEPSEN_ROOT=$(pwd)
cd docker
./up.sh --dev
```

From another terminal:

```
docker exec -it jepsen-control bash
cd knossos
lein install
cd ../rabbitmq
lein run test --help
```

The last command displays the available options. To run a test for 30 seconds with network partition of 10 seconds:

```
lein run test --time-limit 30
```

The first run can take a while because of the provisioning of the nodes. The console output is like the following if the
run is successful:

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