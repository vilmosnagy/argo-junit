package eu.vnagy.argotools.junit.util;

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

import eu.vnagy.argotools.junit.executor.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class WorkflowSummary {

    private WorkflowSummary() {}

    public static String format(WorkflowRun run) {
        StringBuilder out = new StringBuilder();
        String status = run.succeeded() ? "Succeeded" : run.failed() ? "Failed" : run.errored() ? "Errored" : "Unknown";
        out.append("Status:  ").append(status).append('\n').append('\n');

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"STEP", "DURATION", "MESSAGE"});
        collect(run.entrypoint(), " ", true, rows);
        appendTable(out, rows);

        Map<String, String> globals = run.globalParameters();
        if (!globals.isEmpty()) {
            out.append('\n').append("GLOBAL OUTPUTS").append('\n');
            globals.entrySet().stream()
                   .sorted(Map.Entry.comparingByKey())
                   .forEach(e -> out.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n'));
        }

        if (run.hasExitHandler()) {
            WorkflowNode exitHandler = run.exitHandler();
            out.append('\n').append("onExit: ").append(exitHandler.name()).append('\n');
            List<String[]> exitRows = new ArrayList<>();
            collect(exitHandler, " ", true, exitRows);
            appendTable(out, exitRows);
        }

        return out.toString();
    }

    private static void appendTable(StringBuilder out, List<String[]> rows) {
        int stepW = rows.stream().mapToInt(r -> r[0].length()).max().orElse(4);
        int durW  = rows.stream().mapToInt(r -> r[1].length()).max().orElse(8);
        for (String[] row : rows) {
            String line = pad(row[0], stepW) + "  " + pad(row[1], durW) + "  " + row[2];
            out.append(line.stripTrailing()).append('\n');
        }
    }

    private static void collect(WorkflowNode node, String prefix, boolean isRoot,
                                List<String[]> rows) {
        collect(node, prefix, isRoot, null, rows);
    }

    private static void collect(WorkflowNode node, String prefix, boolean isRoot,
                                String itemLabel, List<String[]> rows) {
        List<PodRun.Attempt> podAttempts = node instanceof PodRun pod ? pod.podAttempts() : List.of();
        boolean multiAttemptPod = podAttempts.size() > 1;

        String msg = multiAttemptPod ? podAttempts.size() + " attempts" : message(node);
        if (itemLabel != null && !itemLabel.isEmpty()) {
            String label = itemLabel.length() > 150 ? itemLabel.substring(0, 150) + "..." : itemLabel;
            msg = msg.isEmpty() ? label : label + "  " + msg;
        }

        rows.add(new String[]{
                prefix + icon(node) + " " + node.name(),
                multiAttemptPod ? "" : duration(node),
                msg
        });

        String childPrefix = isRoot ? " " : prefix.substring(0, prefix.length() - 2) +
                (prefix.endsWith("└─") ? "   " : "│  ");

        if (multiAttemptPod) {
            for (int a = 0; a < podAttempts.size(); a++) {
                boolean last = a == podAttempts.size() - 1;
                PodRun.Attempt attempt = podAttempts.get(a);
                rows.add(new String[]{
                        childPrefix + (last ? "└─" : "├─") + "attempt " + (a + 1) + " " + (attempt.succeeded() ? "✔" : "✗"),
                        formatDuration(attempt.duration()),
                        attemptMessage(attempt)
                });
            }
        } else {
            List<Map<String, WorkflowNode>> history = node.attemptHistory();
            if (history.isEmpty()) {
                if (node instanceof ItemRun itemRun) {
                    collectIterations(itemRun, childPrefix, rows);
                } else {
                    collectChildren(node.children(), childPrefix, rows);
                }
            } else {
                int total = history.size() + 1;
                for (int a = 1; a <= total; a++) {
                    boolean last = a == total;
                    boolean attemptOk = last && (node.succeeded() || node.daemoned());
                    rows.add(new String[]{childPrefix + (last ? "└─" : "├─") + "attempt " + a + " " + (attemptOk ? "✔" : "✗"), "", ""});
                    String grandPrefix = childPrefix + (last ? "   " : "│  ");
                    List<WorkflowNode> kids = a <= history.size()
                            ? new ArrayList<>(history.get(a - 1).values())
                            : node.children();
                    collectChildren(kids, grandPrefix, rows);
                }
            }
        }
    }

    private static String attemptMessage(PodRun.Attempt attempt) {
        String cid = attempt.containerId() != null ? attempt.containerId() : "";
        if (attempt.errored()) return cid.isEmpty() ? "error" : "error  " + cid;
        if (!attempt.succeeded()) {
            String base = "exit code " + attempt.exitCode();
            return cid.isEmpty() ? base : base + "  " + cid;
        }
        return cid;
    }

    private static void collectIterations(ItemRun itemRun, String prefix, List<String[]> rows) {
        List<WorkflowNode> iterations = itemRun.children();
        List<String> labels = itemRun.itemLabels();
        for (int i = 0; i < iterations.size(); i++) {
            boolean last = i == iterations.size() - 1;
            String label = i < labels.size() ? labels.get(i) : null;
            collect(iterations.get(i), prefix + (last ? "└─" : "├─"), false, label, rows);
        }
    }

    private static void collectChildren(List<WorkflowNode> children, String prefix, List<String[]> rows) {
        for (int i = 0; i < children.size(); i++) {
            boolean last = i == children.size() - 1;
            collect(children.get(i), prefix + (last ? "└─" : "├─"), false, rows);
        }
    }

    private static String icon(WorkflowNode node) {
        if (node.succeeded()) return "✔";
        if (node.failed())    return "✗";
        if (node.errored())   return "✗";
        if (node.skipped())   return "○";
        if (node.omitted())   return "○";
        if (node.running())   return "◷";
        if (node.pending())   return "·";
        return "?";
    }

    private static String duration(WorkflowNode node) {
        if (node instanceof PodRun pod && !pod.skipped() && !pod.omitted()) {
            if (pod.running() && pod.startedAt() != null) {
                return formatDuration(Duration.between(pod.startedAt(), java.time.Instant.now()));
            }
            return formatDuration(pod.duration());
        }
        return "";
    }

    private static String message(WorkflowNode node) {
        if (node.omitted()) return "omitted";
        if (node.skipped()) return "skipped";
        if (node instanceof PodRun pod) {
            List<PodRun.Attempt> attempts = pod.podAttempts();
            String cid = attempts.isEmpty() ? ""
                    : (attempts.get(attempts.size() - 1).containerId() != null
                       ? attempts.get(attempts.size() - 1).containerId() : "");
            if (pod.errored()) {
                String msg = pod.message().isEmpty() ? "error" : pod.message();
                return cid.isEmpty() ? msg : msg + "  " + cid;
            }
            if (pod.failed())  return cid.isEmpty() ? "exit code " + pod.exitCode()
                                                    : "exit code " + pod.exitCode() + "  " + cid;
            return cid;
        }
        String retries = node.attempts() > 1 ? node.attempts() + " attempts" : "";
        if (node.errored()) return retries.isEmpty() ? "error" : "error, " + retries;
        return retries;
    }

    private static String formatDuration(Duration d) {
        long s = d.getSeconds();
        if (s < 60) return s + "s";
        return (s / 60) + "m " + (s % 60) + "s";
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }
}
