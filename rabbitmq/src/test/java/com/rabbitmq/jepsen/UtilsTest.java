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

import clojure.lang.IPersistentVector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

public class UtilsTest {

    @ParameterizedTest
    @ValueSource(classes = {Utils.AsynchronousConsumerClient.class, Utils.BasicGetClient.class})
    public void allMessagesPublishedAreConsumed(Class<Utils.Client> clientClass) throws Exception {
        int clientCount = 5;

        List<Utils.Client> clients = new ArrayList<>(clientCount);
        for (int i = 0; i < clientCount; i++) {
            Utils.Client client = clientClass.getConstructor(String.class).newInstance("localhost");
            clients.add(client);
            client.setup();
        }

        int operationCount = 50;
        int operationIndex = 0;
        int value = 1;
        List<Integer> publishedValues = new ArrayList<>();
        List<Integer> consumedValues = new ArrayList<>();
        Random random = new Random();
        while (operationIndex < operationCount) {
            Utils.Client client = clients.get(random.nextInt(clientCount));
            // try to reconnect randomly
            if (random.nextInt(10) > 7) {
                client.reconnect();
            }
            if (random.nextBoolean()) {
                client.enqueue(value, 5000);
                publishedValues.add(value);
                value++;
            } else {
                Integer consumedValue = client.dequeue();
                if (consumedValue != null) {
                    consumedValues.add(consumedValue);
                }
            }
            operationIndex++;
        }

        for (Utils.Client client : clients) {
            IPersistentVector drained = client.drain();
            for (int i = 0; i < drained.length(); i++) {
                consumedValues.add(((Number) drained.nth(i)).intValue());
            }
            client.close();
        }

        assertThat(consumedValues)
                .hasSameSizeAs(publishedValues)
                .containsExactlyInAnyOrderElementsOf(publishedValues);
    }

}
