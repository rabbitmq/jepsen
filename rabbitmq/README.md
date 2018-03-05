# jepsen.rabbitmq

The jepsen test to check enqueue/dequeue consistensy in a rabbitmq queue.

This test is created to detect message loss in clustered RabbitMQ.

By default it will create a mirrored queue and enqueue/dequeue messages.

Checkers will validate that all messages enqueued were dequeued.


# Usage

This test uses an old jepsen API. To run the test use the `test` leiningen command:

```
lein test
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

