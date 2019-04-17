# Jepsen Tests for RabbitMQ


## How to run

From the root of the directory:

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
lein run test
```