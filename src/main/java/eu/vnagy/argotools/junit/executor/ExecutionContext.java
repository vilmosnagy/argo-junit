package eu.vnagy.argotools.junit.executor;

import eu.vnagy.argotools.junit.model.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class ExecutionContext {

    private static final Logger log = LoggerFactory.getLogger(ExecutionContext.class);

    private static final Pattern STEP_OUTPUT_RESULT =
            Pattern.compile("\\{\\{steps\\.([^.}]+)\\.outputs\\.result\\}\\}");
    private static final Pattern STEP_IP =
            Pattern.compile("\\{\\{steps\\.([^.}]+)\\.ip\\}\\}");
    private static final Pattern TASK_IP =
            Pattern.compile("\\{\\{tasks\\.([^.}]+)\\.ip\\}\\}");
    private static final Pattern INPUTS_PARAMETER =
            Pattern.compile("\\{\\{inputs\\.parameters\\.([^}]+)\\}\\}");
    private static final Pattern WORKFLOW_PARAMETER =
            Pattern.compile("\\{\\{workflow\\.parameters\\.([^}]+)\\}\\}");

    final Map<String, Template> templateMap;
    final ConcurrentHashMap<String, String> stepOutputResults;
    final ConcurrentHashMap<String, String> stepIps;
    final ConcurrentHashMap<String, String> taskIps;
    final Map<String, String> workflowParams;
    final ExecutorService threadPool;

    ExecutionContext(Map<String, Template> templateMap, Map<String, String> workflowParams,
                    ExecutorService threadPool) {
        this.templateMap = templateMap;
        this.workflowParams = workflowParams;
        this.threadPool = threadPool;
        this.stepOutputResults = new ConcurrentHashMap<>();
        this.stepIps = new ConcurrentHashMap<>();
        this.taskIps = new ConcurrentHashMap<>();
    }

    ExecutionContext childScope() {
        return new ExecutionContext(templateMap, workflowParams, threadPool);
    }

    String substitute(String expr, Map<String, String> inputParams) {
        String result = applyPattern(expr, STEP_OUTPUT_RESULT, stepOutputResults);
        result = applyPattern(result, STEP_IP, stepIps);
        result = applyPattern(result, TASK_IP, taskIps);
        result = applyPattern(result, INPUTS_PARAMETER, inputParams);
        result = applyPattern(result, WORKFLOW_PARAMETER, workflowParams);
        if (!result.equals(expr)) {
            log.trace("Substitute: '{}' → '{}'", expr, result);
        }
        return result;
    }

    List<String> substituteAll(List<String> strings, Map<String, String> inputParams) {
        return strings.stream()
                .map(s -> substitute(s, inputParams))
                .collect(Collectors.toList());
    }

    boolean evaluateWhen(String condition) {
        condition = condition.trim();
        if (condition.contains(" == ")) {
            String[] parts = condition.split(" == ", 2);
            return parts[0].trim().equals(parts[1].trim());
        }
        if (condition.contains(" != ")) {
            String[] parts = condition.split(" != ", 2);
            return !parts[0].trim().equals(parts[1].trim());
        }
        return Boolean.parseBoolean(condition);
    }

    private String applyPattern(String expr, Pattern pattern, Map<String, String> values) {
        Matcher m = pattern.matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = values.getOrDefault(key, m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
