/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.time;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class DefaultEventClock implements EventClock {

    private final Lock lock = new ReentrantLock();
    private final TimeProvider timeProvider;

    private final long clockEpochMillis;
    private final long clockEpochNanos;

    private long mostRecentNanos;
    private int nextOrdinal;

    private boolean postClockEpochTimestampIssued;
    private long mostRecentPreClockEpochMillis;

    public DefaultEventClock(TimeProvider timeProvider) {
        this.timeProvider = timeProvider;
        this.clockEpochMillis = timeProvider.getCurrentTime();
        this.clockEpochNanos = timeProvider.getNanoTime();

        this.mostRecentNanos = clockEpochNanos;
        this.mostRecentPreClockEpochMillis = Long.MIN_VALUE;
    }

    public Timestamp timestamp() {
        lock.lock();
        try {
            postClockEpochTimestampIssued = true;
            return calculateTimestamp(nextOrdinal++);
        } finally {
            lock.unlock();
        }
    }

    public Timestamp timestampProvided(long actualTimeMillis) {
        lock.lock();
        try {
            postClockEpochTimestampIssued = true;
            return calculateTimestampFromProvided(actualTimeMillis, nextOrdinal++);
        } finally {
            lock.unlock();
        }
    }

    public Timestamp timestampPreClockEpochWallTime(long actualTimeMillis) {
        lock.lock();
        try {
            assert !postClockEpochTimestampIssued : "Cannot issue pre epoch timestamp after issuing post epoch timestamp";
            return calculateTimestampPreClockEpochWallTime(actualTimeMillis, nextOrdinal++);
        } finally {
            lock.unlock();
        }
    }

    private Timestamp calculateTimestamp(int ordinal) {
        long nanos = timeProvider.getNanoTime();
        long millis = toUnixEpochMillis(nanos);
        return calculate(nanos, millis, ordinal);
    }

    private Timestamp calculateTimestampFromProvided(long millis, int ordinal) {
        long millisFromEpoch = millis - clockEpochMillis;
        long nanosFromEpoch = NANOSECONDS.convert(millisFromEpoch, TimeUnit.MILLISECONDS);
        long nanos = clockEpochNanos + nanosFromEpoch;
        return calculate(nanos, millis, ordinal);
    }

    private Timestamp calculateTimestampPreClockEpochWallTime(long millis, int ordinal) {
        if (millis >= mostRecentPreClockEpochMillis) {
            if (millis <= clockEpochMillis) {
                mostRecentPreClockEpochMillis = millis;
                return new Timestamp(millis, ordinal);
            } else { // millis is past the epoch
                mostRecentPreClockEpochMillis = clockEpochMillis;
                return new Timestamp(clockEpochMillis, millis, ordinal);
            }
        } else { // we've gone back in time
            return new Timestamp(mostRecentPreClockEpochMillis, millis, ordinal);
        }
    }

    private Timestamp calculate(long nanos, long millis, int ordinal) {
        if (nanos >= mostRecentNanos) {
            mostRecentNanos = nanos;
            return new Timestamp(millis, ordinal);
        } else {
            long maxMillis = toUnixEpochMillis(mostRecentNanos);
            if (maxMillis == millis) { // nanos is > mostRecentNanos, but not by more than 1 millisecond
                return new Timestamp(millis, ordinal);
            } else {
                return new Timestamp(maxMillis, millis, ordinal);
            }
        }
    }

    private long toUnixEpochMillis(long nanoTimestamp) {
        long nanoDelta = nanoTimestamp - clockEpochNanos;
        long millisDelta = MILLISECONDS.convert(nanoDelta, NANOSECONDS);
        return clockEpochMillis + millisDelta;
    }

}
