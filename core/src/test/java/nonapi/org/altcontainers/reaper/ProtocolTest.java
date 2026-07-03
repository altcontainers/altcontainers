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

import org.altcontainers.api.Version;
import org.junit.jupiter.api.Test;

class ProtocolTest {

    @Test
    void parseHeartbeatCommand() {
        Protocol.Command cmd = Protocol.parse("HEARTBEAT");
        assertThat(cmd).isNotNull();
    }

    @Test
    void parseTerminateCommand() {
        Protocol.Command cmd = Protocol.parse("TERMINATE");
        assertThat(cmd).isNotNull();
    }

    @Test
    void parseVersionCommand() {
        String version = Version.version();
        Protocol.Command cmd = Protocol.parse("VERSION " + version);
        assertThat(cmd).isNotNull();
        assertThat(cmd.verb()).isEqualTo("VERSION");
        assertThat(cmd.arg1()).isEqualTo(version);
    }

    @Test
    void parseConnectCommand() {
        Protocol.Command cmd = Protocol.parse("CONNECT abc-123 30000");
        assertThat(cmd).isNotNull();
        assertThat(cmd.verb()).isEqualTo("CONNECT");
        assertThat(cmd.arg1()).isEqualTo("abc-123");
        assertThat(cmd.arg2()).isEqualTo("30000");
    }

    @Test
    void rejectsRegisterVerb() {
        assertThat(Protocol.parse("REGISTER CONTAINER cid123")).isNull();
    }

    @Test
    void rejectsUnregisterVerb() {
        assertThat(Protocol.parse("UNREGISTER CONTAINER cid123")).isNull();
    }

    @Test
    void rejectsDeregisterVerb() {
        assertThat(Protocol.parse("DEREGISTER CONTAINER cid123")).isNull();
    }

    @Test
    void rejectsMissingArgsForConnect() {
        assertThat(Protocol.parse("CONNECT abc-123")).isNull();
    }

    @Test
    void rejectsExtraArgsForConnect() {
        assertThat(Protocol.parse("CONNECT abc-123 30000 extra")).isNull();
    }

    @Test
    void rejectsExtraArgsForHeartbeat() {
        assertThat(Protocol.parse("HEARTBEAT extra")).isNull();
    }

    @Test
    void rejectsExtraArgsForTerminate() {
        assertThat(Protocol.parse("TERMINATE extra")).isNull();
    }

    @Test
    void parseEmptyLine() {
        assertThat(Protocol.parse("")).isNull();
    }

    @Test
    void parseNull() {
        assertThat(Protocol.parse(null)).isNull();
    }

    @Test
    void parseUnknownVerb() {
        assertThat(Protocol.parse("FOO bar")).isNull();
    }

    @Test
    void rejectsVersionWithoutArgs() {
        assertThat(Protocol.parse("VERSION")).isNull();
    }

    @Test
    void rejectsVersionWithExtraArgs() {
        assertThat(Protocol.parse("VERSION 1.0 extra")).isNull();
    }
}
