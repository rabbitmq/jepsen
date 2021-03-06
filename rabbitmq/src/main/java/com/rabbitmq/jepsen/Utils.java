/*
 * Copyright (c) 2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rabbitmq.jepsen;

import clojure.java.api.Clojure;
import clojure.lang.IPersistentVector;
import com.rabbitmq.client.*;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Utils {

  private static final String QUEUE = "jepsen.queue";
  private static final ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool();
  static Logger LOGGER = Logger.getLogger("jepsen.client.utils");

  public static Client createClient(Map<Object, Object> test, Object node) throws Exception {
    Object consumerTypeParameter = get(test, ":consumer-type");
    String consumerType;
    if (consumerTypeParameter == null) {
      consumerType = "asynchronous";
    } else {
      consumerType = consumerTypeParameter.toString();
    }

    Client client;
    if ("asynchronous".equals(consumerType)) {
      client = new AsynchronousConsumerClient(node.toString());
    } else if ("polling".equals(consumerType)) {
      client = new BasicGetClient(node.toString());
    } else if ("mixed".equals(consumerType)) {
      Random random = new Random();
      if (random.nextBoolean()) {
        client = new AsynchronousConsumerClient(node.toString());
      } else {
        client = new BasicGetClient(node.toString());
      }
    } else {
      throw new IllegalArgumentException("Unknown consumer type: " + consumerType);
    }
    client = new LoggingClient(client);
    return client;
  }

  static Object get(Map<Object, Object> map, String keyStringValue) {
    for (Map.Entry<Object, Object> entry : map.entrySet()) {
      if (keyStringValue.equals(entry.getKey().toString())) {
        return entry.getValue();
      }
    }
    return null;
  }

  public static void setup(Client client) throws Exception {
    client.setup();
  }

  public static void close(Client client) throws Exception {
    client.close();
  }

  public static boolean enqueue(Client client, Object value, Number publishConfirmTimeout)
      throws Exception {
    return client.enqueue(value, publishConfirmTimeout);
  }

  public static Number dequeue(Client client) throws Exception {
    return client.dequeue();
  }

  public static IPersistentVector drain(Client client) throws Exception {
    return client.drain();
  }

  public static void reconnect(Client client) throws Exception {
    client.reconnect();
  }

  private static IPersistentVector toClojureVector(Collection<Integer> values) {
    StringBuilder builder = new StringBuilder("[");
    values.forEach(v -> builder.append(v).append(" "));
    builder.append("]");
    return (IPersistentVector) Clojure.read(builder.toString());
  }

  public interface Client {

    void setup() throws Exception;

    boolean enqueue(Object value, Number publishConfirmTimeout) throws Exception;

    Integer dequeue() throws Exception;

    IPersistentVector drain() throws Exception;

    void close() throws Exception;

    void reconnect() throws Exception;
  }

  private static class LoggingClient implements Client {

    private final Client delegate;

    private LoggingClient(Client delegate) {
      this.delegate = delegate;
    }

    @Override
    public void setup() throws Exception {
      try {
        delegate.setup();
      } catch (Exception e) {
        log("setup", e);
        throw e;
      }
    }

    @Override
    public boolean enqueue(Object value, Number publishConfirmTimeout) throws Exception {
      try {
        return delegate.enqueue(value, publishConfirmTimeout);
      } catch (Exception e) {
        log("enqueue", e);
        throw e;
      }
    }

    @Override
    public Integer dequeue() throws Exception {
      try {
        return delegate.dequeue();
      } catch (Exception e) {
        log("dequeue", e);
        throw e;
      }
    }

    @Override
    public IPersistentVector drain() throws Exception {
      try {
        return delegate.drain();
      } catch (Exception e) {
        log("drain", e);
        throw e;
      }
    }

    @Override
    public void close() throws Exception {
      try {
        delegate.close();
      } catch (Exception e) {
        log("close", e);
        throw e;
      }
    }

    @Override
    public void reconnect() throws Exception {
      try {
        delegate.reconnect();
      } catch (Exception e) {
        log("reconnect", e);
        throw e;
      }
    }

    private void log(String method, Exception exception) {
      LOGGER.info(
          delegate
              + ", method "
              + method
              + " has failed: "
              + exception.getClass().getSimpleName()
              + " "
              + exception.getMessage());
    }
  }

  private abstract static class AbstractClient implements Client {

    static final AtomicInteger IDS = new AtomicInteger(0);
    protected final Integer id;
    protected final String host;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    protected volatile Connection connection;

    protected AtomicBoolean closed = new AtomicBoolean(false);

    protected volatile Channel publishingChannel, consumingChannel;

    protected AbstractClient(String host) throws Exception {
      this.host = host;
      this.connection = createConnection();
      id = IDS.incrementAndGet();
    }

    protected Connection createConnection() throws Exception {
      ConnectionFactory cf = new ConnectionFactory();
      cf.setAutomaticRecoveryEnabled(false);
      cf.setHost(this.host);
      return cf.newConnection();
    }

    public void initializeIfNecessary() throws Exception {
      if (initialized.compareAndSet(false, true)) {
        initialize();
      }
    }

    protected abstract void initialize() throws Exception;

    @Override
    public void setup() throws Exception {
      try (Channel ch = connection.createChannel()) {
        ch.queueDeclare(
            QUEUE, true, false, false, Collections.singletonMap("x-queue-type", "quorum"));
        Thread.sleep(1000);
        ch.queuePurge(QUEUE);
      }
    }

    public boolean enqueue(Object value, Number publishConfirmTimeout) throws Exception {
      initializeIfNecessary();
      publishingChannel.basicPublish(
          "",
          QUEUE,
          true,
          new AMQP.BasicProperties.Builder().deliveryMode(2).build(),
          value.toString().getBytes());
      return publishingChannel.waitForConfirms(publishConfirmTimeout.intValue());
    }

    protected Integer asyncDequeue(AtomicBoolean timedOut, Callable<Integer> dequeueAction)
        throws Exception {
      Future<Integer> task = EXECUTOR_SERVICE.submit(dequeueAction);
      try {
        return task.get(5, TimeUnit.SECONDS);
      } catch (TimeoutException te) {
        timedOut.set(true);
        try {
          task.cancel(true);
        } catch (Exception e) {
          log("Exception while cancelling task " + e.getMessage());
        }
        throw te;
      }
    }

    public void close() throws Exception {
      if (closed.compareAndSet(false, true)) {
        connection.close(5000);
      }
    }

    protected void log(String message) {
      LOGGER.info("Client " + host + ": " + message);
    }

    public IPersistentVector drain(
        AtomicBoolean drainedAlready, Collection<? extends AbstractClient> clients)
        throws Exception {
      if (drainedAlready.compareAndSet(false, true)) {
        for (AbstractClient client : clients) {
          try {
            client.close();
          } catch (Exception e) {
          }
        }
        Thread.sleep(5000L);
        Collection<Integer> values = new ArrayList<>();
        Connection c = createConnection();
        Channel ch = c.createChannel();
        GetResponse getResponse;
        while ((getResponse = ch.basicGet(QUEUE, false)) != null) {
          try {
            Integer value = Integer.valueOf(new String(getResponse.getBody()));
            values.add(value);
            ch.basicAck(getResponse.getEnvelope().getDeliveryTag(), false);
          } catch (Exception e) {
            // ignoring, we want to drain
          }
        }
        return toClojureVector(values);
      } else {
        return toClojureVector(new ArrayList<>());
      }
    }
  }

  static class AsynchronousConsumerClient extends AbstractClient {

    private static final Collection<AsynchronousConsumerClient> CLIENTS =
        new CopyOnWriteArrayList<>();
    private static final AtomicBoolean DRAINED = new AtomicBoolean(false);
    private final Queue<Delivery> enqueued = new ConcurrentLinkedDeque<>();
    private final CountDownLatch cancelOkLatch = new CountDownLatch(1);

    public AsynchronousConsumerClient(String host) throws Exception {
      super(host);
      CLIENTS.add(this);
    }

    public Integer dequeue() throws Exception {
      AtomicBoolean timedOut = new AtomicBoolean(false);
      return asyncDequeue(
          timedOut,
          () -> {
            initializeIfNecessary();
            if (Thread.currentThread().isInterrupted() || timedOut.get()) {
              return null;
            }
            Delivery delivery = enqueued.poll();
            if (delivery == null) {
              return null;
            } else {
              Integer value = Integer.valueOf(new String(delivery.getBody()));
              log("Async consumer: dequeued " + value);
              if (Thread.currentThread().isInterrupted() || timedOut.get()) {
                log(
                    "Async consumer: worker thread interrupted, returning "
                        + value
                        + " to in-memory queue");
                enqueued.offer(delivery);
                return null;
              }
              consumingChannel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
              log("Async consumer: ack-ed " + value);
              if (Thread.currentThread().isInterrupted() || timedOut.get()) {
                log(
                    "dequeue for "
                        + value
                        + " has timed out while ack-ing, Jepsen can count this as a lost message");
              }
              return value;
            }
          });
    }

    public IPersistentVector drain() throws Exception {
      return drain(DRAINED, CLIENTS);
    }

    protected void initialize() throws Exception {
      consumingChannel = this.connection.createChannel();
      // TODO make QoS configurable?
      consumingChannel.basicQos(1);
      consumingChannel.basicConsume(
          QUEUE,
          false,
          (consumerTag, message) -> {
            Integer value = Integer.valueOf(new String(message.getBody()));
            log("Received " + value + ". Enqueuing it in client in-memory queue.");
            enqueued.offer(message);
            log("Enqueued: " + value);
          },
          (consumerTag -> cancelOkLatch.countDown()));

      publishingChannel = connection.createChannel();
      publishingChannel.confirmSelect();
    }

    @Override
    public void reconnect() throws Exception {
      try {
        // the client close() is protected to be idempotent, so we don't call it here.
        // we need the connection to be closed to make sure messages in the in-memory
        // go back to the broker
        connection.close(5000);
      } catch (Exception e) {
      }
      enqueued.clear(); // not acked anyway, so go back on the queue when connection is closed
      this.connection = createConnection();
      initialize();
    }

    @Override
    public String toString() {
      return "Async Client [" + host + "]";
    }
  }

  static class BasicGetClient extends AbstractClient {

    private static final Collection<BasicGetClient> CLIENTS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean DRAINED = new AtomicBoolean(false);

    public BasicGetClient(String host) throws Exception {
      super(host);
      CLIENTS.add(this);
    }

    @Override
    public Integer dequeue() throws Exception {
      AtomicBoolean timedOut = new AtomicBoolean(false);
      return asyncDequeue(
          timedOut,
          () -> {
            initializeIfNecessary();
            if (Thread.currentThread().isInterrupted() || timedOut.get()) {
              return null;
            }
            GetResponse getResponse = consumingChannel.basicGet(QUEUE, false);
            if (getResponse == null) {
              return null;
            } else {
              Integer value = Integer.valueOf(new String(getResponse.getBody()));
              log("Dequeued " + value);
              if (Thread.currentThread().isInterrupted() || timedOut.get()) {
                log("Worker thread interrupted, not ack-ing " + value + ", re-queueing it");
                // the dequeue may have timed out, requeueing could avoid keeping this message
                consumingChannel.basicReject(getResponse.getEnvelope().getDeliveryTag(), true);
                return null;
              }
              consumingChannel.basicAck(getResponse.getEnvelope().getDeliveryTag(), false);
              log("Ack-ed " + value + ", returning it to Jepsen");
              if (Thread.currentThread().isInterrupted() || timedOut.get()) {
                log(
                    "dequeue for "
                        + value
                        + " has timed out while ack-ing, Jepsen can count this as a lost message");
              }
              return value;
            }
          });
    }

    @Override
    public IPersistentVector drain() throws Exception {
      return drain(DRAINED, CLIENTS);
    }

    protected void initialize() throws Exception {
      publishingChannel = connection.createChannel();
      publishingChannel.confirmSelect();
      consumingChannel = connection.createChannel();
    }

    @Override
    public void reconnect() throws Exception {
      try {
        close();
      } catch (Exception e) {
      }
      this.connection = createConnection();
      initialize();
    }

    @Override
    public String toString() {
      return "BasicGet Client [" + host + "]";
    }
  }
}
