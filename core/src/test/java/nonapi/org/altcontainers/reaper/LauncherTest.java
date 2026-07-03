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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LauncherTest {

    private static final String OS_NAME_KEY = "os.name";

    private String originalOsName;

    @BeforeEach
    void saveOsName() {
        originalOsName = System.getProperty(OS_NAME_KEY);
    }

    @AfterEach
    void restoreOsName() {
        if (originalOsName != null) {
            System.setProperty(OS_NAME_KEY, originalOsName);
        } else {
            System.clearProperty(OS_NAME_KEY);
        }
    }

    @Test
    void isLinuxReturnsTrueForLinuxOsName() {
        System.setProperty(OS_NAME_KEY, "Linux");
        assertThat(Launcher.isLinux()).isTrue();
    }

    @Test
    void isLinuxReturnsFalseForMacOsName() {
        System.setProperty(OS_NAME_KEY, "Mac OS X");
        assertThat(Launcher.isLinux()).isFalse();
    }

    @Test
    void isLinuxReturnsFalseForWindowsOsName() {
        System.setProperty(OS_NAME_KEY, "Windows 10");
        assertThat(Launcher.isLinux()).isFalse();
    }

    @Test
    void isLinuxReturnsFalseForEmptyOsName() {
        System.setProperty(OS_NAME_KEY, "");
        assertThat(Launcher.isLinux()).isFalse();
    }

    @Test
    void isSetsidAvailableRestoresInterruptFlag() {
        Thread.currentThread().interrupt();

        @SuppressWarnings("unused")
        boolean unused = Launcher.isSetsidAvailable();

        assertThat(Thread.currentThread().isInterrupted())
                .as("isSetsidAvailable must restore the interrupt flag after waitFor interruption")
                .isTrue();
    }
}
