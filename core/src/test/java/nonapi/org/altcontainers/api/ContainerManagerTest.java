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

package nonapi.org.altcontainers.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Map;
import org.altcontainers.api.ContainerException;
import org.junit.jupiter.api.Test;

/**
 * Verifies the log stream error suppression guard used by the
 * {@code onError} callback in {@link ContainerManager#startLogStream}.
 */
class ContainerManagerTest {

    @Test
    void shouldReturnTrueForDirectClosedByInterruptException() {
        ClosedByInterruptException ex = new ClosedByInterruptException();
        assertThat(ContainerManager.isClosedByInterrupt(ex)).isTrue();
    }

    @Test
    void shouldReturnTrueForWrappedClosedByInterruptException() {
        IOException ex = new IOException("wrapper", new ClosedByInterruptException());
        assertThat(ContainerManager.isClosedByInterrupt(ex)).isTrue();
    }

    @Test
    void shouldReturnTrueForDeeplyWrappedClosedByInterruptException() {
        RuntimeException ex = new RuntimeException(new IOException(new ClosedByInterruptException()));
        assertThat(ContainerManager.isClosedByInterrupt(ex)).isTrue();
    }

    @Test
    void shouldReturnFalseForUnrelatedException() {
        IOException ex = new IOException("some unrelated I/O error");
        assertThat(ContainerManager.isClosedByInterrupt(ex)).isFalse();
    }

    @Test
    void shouldReturnFalseForNull() {
        assertThat(ContainerManager.isClosedByInterrupt(null)).isFalse();
    }

    @Test
    void shouldSuppressErrorWhenLogHandleIsClosed() {
        ContainerManager.LogHandle handle = new ContainerManager.LogHandle();
        handle.close();
        assertThat(ContainerManager.shouldSuppressLogError(handle, new IOException("test")))
                .isTrue();
    }

    @Test
    void shouldSuppressErrorWhenExceptionIsClosedByInterrupt() {
        ContainerManager.LogHandle handle = new ContainerManager.LogHandle();
        assertThat(ContainerManager.shouldSuppressLogError(handle, new ClosedByInterruptException()))
                .isTrue();
    }

    @Test
    void shouldNotSuppressErrorForUnrelatedExceptionWithOpenHandle() {
        ContainerManager.LogHandle handle = new ContainerManager.LogHandle();
        assertThat(ContainerManager.shouldSuppressLogError(handle, new IOException("genuine stream error")))
                .isFalse();
    }

    @Test
    void parsePortBindingsShouldReturnEmptyMapWhenPortsIsNull() {
        assertThat(ContainerManager.parsePortBindings(null)).isEmpty();
    }

    @Test
    void parsePortBindingsShouldReturnEmptyMapWhenPortsHasNoBindings() {
        Ports ports = new Ports();
        assertThat(ContainerManager.parsePortBindings(ports)).isEmpty();
    }

    @Test
    void parsePortBindingsShouldReturnExpectedBindings() {
        Ports ports = new Ports();
        ports.bind(ExposedPort.tcp(80), Ports.Binding.bindPort(8080));
        ports.bind(ExposedPort.tcp(443), Ports.Binding.bindPort(8443));

        Map<Integer, Integer> result = ContainerManager.parsePortBindings(ports);
        assertThat(result).containsEntry(80, 8080).containsEntry(443, 8443).hasSize(2);
    }

    @Test
    void parsePortBindingsShouldThrowForNonNumericHostPortSpec() {
        Ports ports = new Ports();
        Ports.Binding badBinding = new Ports.Binding("0.0.0.0", "not-a-number");
        ports.bind(ExposedPort.tcp(80), badBinding);

        assertThatThrownBy(() -> ContainerManager.parsePortBindings(ports))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("not-a-number")
                .hasMessageContaining("80");
    }
}
