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

/**
 * Unit test for Finding 3 — verifies that shutdown-hook-read fields in
 * {@link ResourceController} are declared {@code volatile}.
 */
class ResourceControllerVolatileTest {

    @Test
    void shutdownHookFieldsAreVolatile() throws Exception {
        assertThat(modifiers("socket")).isTrue();
        assertThat(modifiers("reader")).isTrue();
        assertThat(modifiers("writer")).isTrue();
        assertThat(modifiers("heartbeatThread")).isTrue();
        assertThat(modifiers("heartbeat")).isTrue();
    }

    private static boolean modifiers(String fieldName) throws Exception {
        var field = ResourceController.class.getDeclaredField(fieldName);
        return Modifier.isVolatile(field.getModifiers());
    }
}
