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

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Utils {

    private static final String QUEUE = "jepsen.queue";

    public static Client createClient(Map<Object, Object> test, Object node) throws Exception {
        Object consumerTypeParameter = get(test, ":consumer-type");
        String consumerType;
        if (consumerTypeParameter == null) {
            consumerType = "asynchronous";
        } else {
            consumerType = consumerTypeParameter.toString();
        }

        if ("asynchronous".equals(consumerType)) {
            return new AsynchronousConsumerClient(node.toString());
        } else if ("polling".equals(consumerType)) {
            return new BasicGetClient(node.toString());
        } else if ("mixed".equals(consumerType)) {
            Random random = new Random();
            if (random.nextBoolean()) {
                return new AsynchronousConsumerClient(node.toString());
            } else {
                return new BasicGetClient(node.toString());
            }
        } else {
            throw new IllegalArgumentException("Unknown consumer type: " + consumerType);
        }
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

    public static boolean enqueue(Client client, Object value, Number publishConfirmTimeout) throws Exception {
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

    private static abstract class AbstractClient implements Client {

        private final String host;

        private final AtomicBoolean initialized = new AtomicBoolean(false);

        protected volatile Connection connection;

        protected volatile Channel publishingChannel, consumingChannel;

        protected AbstractClient(String host) throws Exception {
            this.host = host;
            this.connection = createConnection();
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

        protected boolean isInitialized() {
            return initialized.get();
        }

        protected abstract void initialize() throws Exception;

        @Override
        public void setup() throws Exception {
            try (Channel ch = connection.createChannel()) {
                ch.queueDeclare(QUEUE, true, false, false, Collections.singletonMap("x-queue-type", "quorum"));
                Thread.sleep(1000);
                ch.queuePurge(QUEUE);
            }
        }

        public boolean enqueue(Object value, Number publishConfirmTimeout) throws Exception {
            initializeIfNecessary();
            publishingChannel.basicPublish("", QUEUE,
                    true,
                    new AMQP.BasicProperties.Builder().deliveryMode(2).build(),
                    value.toString().getBytes()
            );
            return publishingChannel.waitForConfirms(publishConfirmTimeout.intValue());
        }

        public void close() throws Exception {
            connection.close(5000);
        }

    }

    static class AsynchronousConsumerClient extends AbstractClient {

        private final Queue<Delivery> enqueued = new ConcurrentLinkedDeque<>();
        private final CountDownLatch cancelOkLatch = new CountDownLatch(1);

        private volatile String consumerTag;

        public AsynchronousConsumerClient(String host) throws Exception {
            super(host);
        }

        public Integer dequeue() throws Exception {
            initializeIfNecessary();
            Delivery delivery = enqueued.poll();
            if (delivery == null) {
                return null;
            } else {
                consumingChannel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                return Integer.valueOf(new String(delivery.getBody()));
            }
        }

        public IPersistentVector drain() throws Exception {
            Collection<Integer> values = new CopyOnWriteArrayList<>();
            if (isInitialized()) {
                consumingChannel.basicCancel(consumerTag);
                // doing our best to wait for the long-running consumer to be stopped
                // to drain the local queue safely
                cancelOkLatch.await(1, TimeUnit.SECONDS);
                Integer value = dequeue();
                while (value != null) {
                    values.add(value);
                    value = dequeue();
                }
                try {
                    consumingChannel.close();
                } catch (Exception e) {
                    // ignoring, we want to drain
                }
            }

            Channel ch = connection.createChannel();
            ch.basicConsume(QUEUE, false, (ctag, delivery) -> {
                values.add(Integer.valueOf(new String(delivery.getBody())));
                ch.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            }, ctag -> {
            });

            consumingChannel = connection.createChannel();
            while (consumingChannel.queueDeclarePassive(QUEUE).getMessageCount() != 0) {
                Thread.sleep(100);
            }
            return toClojureVector(values);
        }

        protected void initialize() throws Exception {
            consumingChannel = this.connection.createChannel();
            // TODO make QoS configurable?
            consumingChannel.basicQos(1);
            consumerTag = consumingChannel.basicConsume(QUEUE, false,
                    ((consumerTag, message) -> enqueued.offer(message)),
                    (consumerTag -> cancelOkLatch.countDown()));

            publishingChannel = connection.createChannel();
            publishingChannel.confirmSelect();
        }

        @Override
        public void reconnect() throws Exception {
            try {
                close();
            } catch (Exception e) {
            }
            enqueued.clear(); // not acked anyway, so go back on the queue when connection is closed
            this.connection = createConnection();
            initialize();
        }
    }

    static class BasicGetClient extends AbstractClient {

        public BasicGetClient(String host) throws Exception {
            super(host);
        }

        @Override
        public Integer dequeue() throws Exception {
            initializeIfNecessary();
            GetResponse getResponse = consumingChannel.basicGet(QUEUE, false);
            if (getResponse == null) {
                return null;
            } else {
                consumingChannel.basicAck(getResponse.getEnvelope().getDeliveryTag(), false);
                return Integer.valueOf(new String(getResponse.getBody()));
            }
        }

        @Override
        public IPersistentVector drain() throws Exception {
            Collection<Integer> values = new ArrayList<>();
            Integer value = dequeue();
            while (value != null) {
                values.add(value);
                value = dequeue();
            }
            return toClojureVector(values);
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
    }

}
