/*
 * Copyright (c) 2019-2022 Pivotal Software Inc, All Rights Reserved.
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
import com.rabbitmq.jepsen.Utils.AsynchronousConsumerClient;
import com.rabbitmq.jepsen.Utils.BasicGetClient;
import com.rabbitmq.jepsen.Utils.LoggingClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

public class UtilsTest {

  static Object[][] allMessagesPublishedAreConsumed() {
    return new Object[][] {
        {AsynchronousConsumerClient.class, false},
        {AsynchronousConsumerClient.class, true},
        {BasicGetClient.class, false},
        {BasicGetClient.class, true},
    };
  }

  @ParameterizedTest
  @MethodSource
  public void allMessagesPublishedAreConsumed(Class<Utils.Client> clientClass, boolean deadLetterMode) throws Exception {
    Utils.reset();
    int clientCount = 5;

    List<Utils.Client> clients = new ArrayList<>(clientCount);
    for (int i = 0; i < clientCount; i++) {
      Utils.Client client = clientClass.getConstructor(String.class, boolean.class)
          .newInstance("localhost", deadLetterMode);
      clients.add(new LoggingClient(client));
      client.setup();
    }

    int operationCount = 50;
    int operationIndex = 0;
    int value = 1;
    List<Integer> publishedValues = new ArrayList<>();
    // because of reconnection, there can be duplicates
    Set<Integer> consumedValues = new HashSet<>();
    Random random = new Random();
    while (operationIndex < operationCount) {
      Utils.Client client = clients.get(random.nextInt(clientCount));
      // try to reconnect randomly
      if (random.nextInt(10) > 7) {
        client.reconnect();
      }
      if (random.nextBoolean()) {
        boolean published = client.enqueue(value, 5000);
        if (published) {
          publishedValues.add(value);
        }
        value++;
      } else {
        Integer consumedValue = client.dequeue();
        if (consumedValue != null) {
          consumedValues.add(consumedValue);
        }
      }
      operationIndex++;
    }

    if (deadLetterMode) {
      Thread.sleep(Utils.MESSAGE_TTL.toMillis() * 2);
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
