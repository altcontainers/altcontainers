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

import java.util.Map;
import org.junit.jupiter.api.Test;

class ResourceLabelsTest {

    @Test
    void shouldProduceLabelKeys() {
        assertThat(ResourceLabels.MANAGED).isNotBlank();
        assertThat(ResourceLabels.SESSION_ID).isNotBlank();
        assertThat(ResourceLabels.CREATED_AT_MS).isNotBlank();
    }

    @Test
    void shouldBuildFilterMapForSession() {
        Map<String, String> filter = ResourceLabels.filterForSession("abc-123");
        assertThat(filter).containsEntry(ResourceLabels.MANAGED, "true");
        assertThat(filter).containsEntry(ResourceLabels.SESSION_ID, "abc-123");
    }

    @Test
    void shouldBuildFilterMapForManaged() {
        Map<String, String> filter = ResourceLabels.filterForManaged();
        assertThat(filter).containsEntry(ResourceLabels.MANAGED, "true");
        assertThat(filter).hasSize(1);
    }

    @Test
    void shouldParseSessionIdFromLabels() {
        Map<String, String> labels = Map.of(ResourceLabels.SESSION_ID, "abc-123");
        assertThat(ResourceLabels.sessionId(labels)).isEqualTo("abc-123");
    }

    @Test
    void shouldReturnNullForMissingSessionId() {
        Map<String, String> labels = Map.of();
        assertThat(ResourceLabels.sessionId(labels)).isNull();
    }

    @Test
    void shouldParseCreatedAtFromLabels() {
        Map<String, String> labels = Map.of(ResourceLabels.CREATED_AT_MS, "1700000000000");
        assertThat(ResourceLabels.createdAtMs(labels)).isEqualTo(1700000000000L);
    }

    @Test
    void shouldReturnNegativeForMissingLabel() {
        Map<String, String> labels = Map.of();
        assertThat(ResourceLabels.createdAtMs(labels)).isEqualTo(-1L);
    }

    @Test
    void shouldReturnNegativeForMalformedLabel() {
        Map<String, String> labels = Map.of(ResourceLabels.CREATED_AT_MS, "not-a-number");
        assertThat(ResourceLabels.createdAtMs(labels)).isEqualTo(-1L);
    }
}
