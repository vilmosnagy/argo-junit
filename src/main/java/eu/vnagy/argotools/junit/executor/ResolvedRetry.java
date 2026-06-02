package eu.vnagy.argotools.junit.executor;

/*-
 * #%L
 * Argo JUnit
 * %%
 * Copyright (C) 2026 Vilmos Szabó-Nagy
 * %%
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
 * #L%
 */

import eu.vnagy.argotools.junit.model.Backoff;
import eu.vnagy.argotools.junit.model.RetryStrategy;

import java.time.Duration;
import java.time.Instant;

enum RetryPolicy { ON_FAILURE, ON_ERROR, ALWAYS }

record ResolvedRetry(int limit, RetryPolicy policy,
                     Duration backoffDuration, double backoffFactor,
                     Duration backoffCap, Duration backoffMaxDuration) {

    static final ResolvedRetry NONE =
            new ResolvedRetry(0, RetryPolicy.ON_FAILURE, Duration.ZERO, 1.0, Duration.ZERO, Duration.ZERO);

    static ResolvedRetry from(RetryStrategy templateRs, RetryStrategy defaultRs) {
        RetryStrategy rs = templateRs != null ? templateRs : defaultRs;
        if (rs == null) return NONE;
        String limitStr = rs.getLimit() != null ? rs.getLimit()
                : (defaultRs != null ? defaultRs.getLimit() : null);
        int limit = limitStr != null ? Integer.parseInt(limitStr) : -1;
        String pol = rs.getRetryPolicy();
        RetryPolicy policy = "Always".equalsIgnoreCase(pol) ? RetryPolicy.ALWAYS
                : "OnError".equalsIgnoreCase(pol) ? RetryPolicy.ON_ERROR
                : RetryPolicy.ON_FAILURE;
        Backoff b = rs.getBackoff();
        if (b == null) return new ResolvedRetry(limit, policy, Duration.ZERO, 1.0, Duration.ZERO, Duration.ZERO);
        return new ResolvedRetry(limit, policy,
                parseDuration(b.getDuration()),
                b.getFactor() != null ? Double.parseDouble(b.getFactor()) : 1.0,
                b.getCap() != null ? parseDuration(b.getCap()) : Duration.ZERO,
                b.getMaxDuration() != null ? parseDuration(b.getMaxDuration()) : Duration.ZERO);
    }

    /**
     * Returns true if another attempt should be made.
     *
     * @param failed          whether the last attempt failed (non-zero exit / child step failed)
     * @param errored         whether the last attempt errored (infrastructure error)
     * @param completedCount  how many attempts have been made so far (1 = first run just finished)
     */
    boolean shouldRetry(boolean failed, boolean errored, int completedCount) {
        if (limit >= 0 && completedCount > limit) return false;
        return switch (policy) {
            case ON_FAILURE -> failed;
            case ON_ERROR   -> errored;
            case ALWAYS     -> failed || errored;
        };
    }

    boolean withinMaxDuration(Instant retryStart) {
        return backoffMaxDuration.isZero()
                || Duration.between(retryStart, Instant.now()).compareTo(backoffMaxDuration) < 0;
    }

    Duration nextBackoff(Duration current) {
        long nextMs = (long) (current.toMillis() * backoffFactor);
        Duration next = Duration.ofMillis(nextMs);
        return !backoffCap.isZero() && next.compareTo(backoffCap) > 0 ? backoffCap : next;
    }

    static Duration parseDuration(String s) {
        if (s == null || s.isBlank()) return Duration.ZERO;
        s = s.trim();
        try { return Duration.ofSeconds(Long.parseLong(s)); } catch (NumberFormatException ignored) {}
        if (s.endsWith("s")) {
            try { return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1))); } catch (NumberFormatException ignored) {}
        }
        if (s.endsWith("m")) {
            try { return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1))); } catch (NumberFormatException ignored) {}
        }
        if (s.endsWith("h")) {
            try { return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1))); } catch (NumberFormatException ignored) {}
        }
        throw new IllegalArgumentException("Cannot parse Argo duration: '" + s + "'");
    }
}
