package eu.vnagy.argotools.junit.executor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class WorkflowSummary {

    private WorkflowSummary() {}

    public static String format(WorkflowRun run) {
        StringBuilder out = new StringBuilder();
        out.append("Status:  ").append(run.succeeded() ? "Succeeded" : run.failed() ? "Failed" : "Unknown")
           .append('\n').append('\n');

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"STEP", "DURATION", "MESSAGE"});
        collect(run.entrypoint(), " ", true, rows);

        int stepW = rows.stream().mapToInt(r -> r[0].length()).max().orElse(4);
        int durW  = rows.stream().mapToInt(r -> r[1].length()).max().orElse(8);

        for (String[] row : rows) {
            String line = pad(row[0], stepW) + "  " + pad(row[1], durW) + "  " + row[2];
            out.append(line.stripTrailing()).append('\n');
        }

        return out.toString();
    }

    private static void collect(WorkflowNode node, String prefix, boolean isRoot,
                                List<String[]> rows) {
        rows.add(new String[]{prefix + icon(node) + " " + node.name(), duration(node), message(node)});

        String childPrefix = isRoot ? " " : prefix.substring(0, prefix.length() - 2) +
                (prefix.endsWith("└─") ? "   " : "│  ");

        List<WorkflowNode> children = children(node);
        for (int i = 0; i < children.size(); i++) {
            boolean last = i == children.size() - 1;
            collect(children.get(i), childPrefix + (last ? "└─" : "├─"), false, rows);
        }
    }

    private static List<WorkflowNode> children(WorkflowNode node) {
        if (node instanceof StepsRun s) return new ArrayList<>(s.steps());
        if (node instanceof DagRun d)   return new ArrayList<>(d.tasks());
        return List.of();
    }

    private static String icon(WorkflowNode node) {
        if (node.succeeded()) return "✔";
        if (node.failed())    return "✗";
        if (node.skipped())   return "○";
        if (node.running())   return "◷";
        if (node.pending())   return "·";
        return "?";
    }

    private static String duration(WorkflowNode node) {
        if (node instanceof PodRun pod && !pod.skipped()) {
            return formatDuration(pod.duration());
        }
        return "";
    }

    private static String message(WorkflowNode node) {
        if (node.skipped()) return "skipped";
        if (node instanceof PodRun pod && pod.failed()) return "exit code " + pod.exitCode();
        return "";
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
