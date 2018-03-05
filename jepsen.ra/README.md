# jepsen.ra

Jepsen test to check message loss in `ra_fifo` backiend for
[ra](https://github.com/rabbitmq/ra) library.

The test is using `ra_fifo_cli` script, which runs a raft cluster, enqueues and
dequeued messages.

Messages are enqueued and dequeued synchronously and one-by-one. Every time a message
is enqueued or dequeued, new CLI process is run. There is no long-running ra clients.

The test is based on [the rabbitmq testing blog post](https://aphyr.com/posts/315-jepsen-rabbitmq)

# Usage

The test is using the jepsen cli API. To run the test:

```
lein run test
```

You can configure different test settings via command line arguments.
For example to run a test on 3 nodes and a single client process.

```
lein run test --concurrency 1 --node n1 --node n2 --node n3
```

# Output

The test will log messages being enqueued and dequeued. For example:

```
INFO  jepsen.util - 0   :ok :enqueue    1461

INFO  jepsen.util - 0   :invoke :dequeue    nil
INFO  jepsen.util - 0   :ok :dequeue    1442
```

At the end of the test, it will output validation results for two validators:

* queue - checks that every enqueued message was dequeued exactly one time.
* total_queue - analyzes information about enqueued and dequeued messages and outputs:
    * lost - messages successfully enqueued and never dequeued
    * recovered - messages are where we didn’t know if the enqueue was successful, but it was dequeued nonetheless
    * unexpected - messages are those which came out of the queue despite never having been enqueued. This includes cases where the same message is dequeued more than once.
    * duplicated - messages dequeued more than once

Every metric in the `total_queue` has `-frac` field, showing a fraction of the metric
in total messages and also `ok-frac`, which shows fraction of messages, which were
enqueued and dequeued exactly once.

