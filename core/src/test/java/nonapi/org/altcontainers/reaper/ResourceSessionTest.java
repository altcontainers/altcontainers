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

class ResourceSessionTest {

    @Test
    void shouldGenerateUniqueSessionIds() {
        ResourceSession session1 = new ResourceSession();
        ResourceSession session2 = new ResourceSession();
        assertThat(session1.sessionId()).isNotBlank();
        assertThat(session2.sessionId()).isNotBlank();
        assertThat(session1.sessionId()).isNotEqualTo(session2.sessionId());
    }

    @Test
    void shouldProduceLabelsForNewResource() {
        ResourceSession session = new ResourceSession();
        Map<String, String> labels = session.labelsForNewResource();
        assertThat(labels).hasSize(3);
        assertThat(labels).containsKey(ResourceLabels.MANAGED);
        assertThat(labels).containsKey(ResourceLabels.SESSION_ID);
        assertThat(labels).containsKey(ResourceLabels.CREATED_AT_MS);
        assertThat(labels.get(ResourceLabels.MANAGED)).isEqualTo("true");
        assertThat(labels.get(ResourceLabels.SESSION_ID)).isEqualTo(session.sessionId());
    }

    @Test
    void shouldStampFreshCreatedAtPerCall() {
        ResourceSession session = new ResourceSession();
        Map<String, String> labels1 = session.labelsForNewResource();
        Map<String, String> labels2 = session.labelsForNewResource();
        long createdAt1 = Long.parseLong(labels1.get(ResourceLabels.CREATED_AT_MS));
        long createdAt2 = Long.parseLong(labels2.get(ResourceLabels.CREATED_AT_MS));
        assertThat(createdAt1).isPositive();
        assertThat(createdAt2).isPositive();
        long now = System.currentTimeMillis();
        assertThat(createdAt1).isLessThanOrEqualTo(now);
        assertThat(createdAt2).isLessThanOrEqualTo(now);
    }

    @Test
    void shouldCacheSessionIdAcrossLabelCalls() {
        ResourceSession session = new ResourceSession();
        Map<String, String> labels1 = session.labelsForNewResource();
        Map<String, String> labels2 = session.labelsForNewResource();
        assertThat(labels1.get(ResourceLabels.SESSION_ID)).isEqualTo(session.sessionId());
        assertThat(labels2.get(ResourceLabels.SESSION_ID)).isEqualTo(session.sessionId());
    }
}
