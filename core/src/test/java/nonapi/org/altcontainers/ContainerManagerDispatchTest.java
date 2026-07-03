/*
 * Copyright (c) 2026-present Douglas Hoard
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nonapi.org.altcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.LogWaitStrategy;
import org.altcontainers.api.PortWaitStrategy;
import org.altcontainers.api.WaitStrategy;
import org.junit.jupiter.api.Test;

class ContainerManagerDispatchTest {

    @Test
    void createAttemptStatePrecomputesLogConsumers() throws Exception {
        List<WaitStrategy> strategies = List.of(
                LogWaitStrategy.builder().pattern(".*x.*").build(),
                PortWaitStrategy.builder().port(8080).build());

        // Use reflection to call the package-private createAttemptState method
        Method method = ContainerManager.class.getDeclaredMethod("createAttemptState", List.class);
        method.setAccessible(true);

        Object attemptState = method.invoke(null, strategies);

        // Get the logConsumers list from the AttemptState record
        Method logConsumersMethod = attemptState.getClass().getDeclaredMethod("logConsumers");
        @SuppressWarnings("unchecked")
        List<Consumer<String>> logConsumers = (List<Consumer<String>>) logConsumersMethod.invoke(attemptState);

        assertThat(logConsumers).hasSize(1);
    }

    @Test
    void createAttemptStateReturnsFreshStrategies() throws Exception {
        LogWaitStrategy src = LogWaitStrategy.builder().pattern(".*x.*").build();
        src.logLineConsumer().accept("x\n");
        assertThat(src.check(null)).isTrue();

        List<WaitStrategy> strategies = List.of(src);

        Method method = ContainerManager.class.getDeclaredMethod("createAttemptState", List.class);
        method.setAccessible(true);

        Object attemptState = method.invoke(null, strategies);

        // Get the strategies list from the AttemptState record
        Method strategiesMethod = attemptState.getClass().getDeclaredMethod("strategies");
        @SuppressWarnings("unchecked")
        List<WaitStrategy> freshStrategies = (List<WaitStrategy>) strategiesMethod.invoke(attemptState);

        assertThat(freshStrategies).hasSize(1);
        assertThat(freshStrategies.get(0).check(null)).isFalse();

        // Original is unchanged
        assertThat(src.check(null)).isTrue();
    }

    @Test
    void inspectContainerDiagnosticsReturnsEmptyForNonexistentContainer() throws Exception {
        Method method = ContainerOperations.class.getDeclaredMethod(
                "inspectContainerDiagnostics", DockerClient.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(null, DockerClient.instance(), "nonexistent-container-id");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void awaitPortMappingsReturnsImmediatelyForEmptyPortList() throws Exception {
        Method method = ContainerOperations.class.getDeclaredMethod(
                "awaitPortMappings", DockerClient.class, String.class, List.class, Duration.class);
        method.setAccessible(true);

        method.invoke(null, DockerClient.instance(), "nonexistent", List.of(), Duration.ofSeconds(5));
    }

    @Test
    void awaitPortMappingsThrowsForNonexistentContainer() throws Exception {
        Method method = ContainerOperations.class.getDeclaredMethod(
                "awaitPortMappings", DockerClient.class, String.class, List.class, Duration.class);
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(
                        null,
                        DockerClient.instance(),
                        "nonexistent-container-id",
                        List.of(9092),
                        Duration.ofMillis(300)))
                .cause()
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("not ready within");
    }

    @Test
    void dispatchRawLogLineDelegatesToConsumers() throws Exception {
        List<Consumer<String>> consumers = new ArrayList<>();
        List<String> received = new ArrayList<>();
        consumers.add(received::add);

        // Create an AttemptState via createAttemptState with a LogWaitStrategy
        List<WaitStrategy> strategies =
                List.of(LogWaitStrategy.builder().pattern(".*x.*").build());
        Method createMethod = ContainerManager.class.getDeclaredMethod("createAttemptState", List.class);
        createMethod.setAccessible(true);
        Object attemptState = createMethod.invoke(null, strategies);

        // Call dispatchRawLogLine via reflection
        Method dispatchMethod =
                ContainerManager.class.getDeclaredMethod("dispatchRawLogLine", attemptState.getClass(), String.class);
        dispatchMethod.setAccessible(true);
        dispatchMethod.invoke(null, attemptState, "x\n");

        // The log consumer in the attempt state should have been called
        Method strategiesMethod = attemptState.getClass().getDeclaredMethod("strategies");
        @SuppressWarnings("unchecked")
        List<WaitStrategy> fresh = (List<WaitStrategy>) strategiesMethod.invoke(attemptState);
        LogWaitStrategy logStrategy = (LogWaitStrategy) fresh.get(0);
        assertThat(logStrategy.check(null)).isTrue();
    }
}
