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

package org.altcontainers.api;

/**
 * A composite wait strategy that is satisfied when all of its child strategies
 * are satisfied. Short-circuits on the first failing child.
 */
final class AllOfWaitStrategy extends CompositeWaitStrategy {

    AllOfWaitStrategy(WaitStrategy... strategies) {
        super(strategies);
    }

    @Override
    public boolean check(Container container) {
        for (WaitStrategy s : strategies) {
            if (!s.check(container)) {
                return false;
            }
        }
        return true;
    }

    @Override
    CompositeWaitStrategy create(WaitStrategy... strategies) {
        return new AllOfWaitStrategy(strategies);
    }
}
