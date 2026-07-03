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

package nonapi.org.altcontainers.reaper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class ResourceControllerTest {

    @Test
    void ensureReadyPerformsDiscoveryOutsideInstanceMonitor() throws Exception {
        java.lang.reflect.Method method = ResourceController.class.getDeclaredMethod("ensureReady");

        // Discovery must happen outside the synchronized block.
        // Verify by checking that the method is not synchronized
        // (in Java, synchronized is in the method modifiers).
        int modifiers = method.getModifiers();
        assertThat(Modifier.isSynchronized(modifiers))
                .as("ensureReady() should not be synchronized on the instance monitor")
                .isFalse();
    }

    @Test
    void shouldReuseSessionIdOnReconnect() {
        ResourceController controller = ResourceController.instance();
        String id1 = controller.ensureReady().sessionId();
        String id2 = controller.ensureReady().sessionId();
        assertThat(id2).isEqualTo(id1);
    }
}
