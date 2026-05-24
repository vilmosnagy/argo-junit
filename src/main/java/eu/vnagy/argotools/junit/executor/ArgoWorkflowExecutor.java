package eu.vnagy.argotools.junit.executor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.vnagy.argotools.junit.model.Parameter;
import eu.vnagy.argotools.junit.model.Template;
import eu.vnagy.argotools.junit.model.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ArgoWorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(ArgoWorkflowExecutor.class);

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Workflow workflow;

    private ArgoWorkflowExecutor(Workflow workflow) {
        this.workflow = workflow;
    }

    public static ArgoWorkflowExecutor from(Path workflowFile) {
        try {
            return new ArgoWorkflowExecutor(YAML.readValue(Files.newInputStream(workflowFile), Workflow.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read workflow file: " + workflowFile, e);
        }
    }

    public static ArgoWorkflowExecutor from(String workflowYaml) {
        try {
            return new ArgoWorkflowExecutor(YAML.readValue(workflowYaml, Workflow.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse workflow YAML", e);
        }
    }

    public static ArgoWorkflowExecutor from(Workflow workflow) {
        return new ArgoWorkflowExecutor(workflow);
    }

    public WorkflowRun executeAsync() {
        String entrypointName = workflow.getSpec().getEntrypoint();
        log.debug("Entrypoint: {}", entrypointName);

        Map<String, String> workflowParams = new LinkedHashMap<>();
        if (workflow.getSpec().getArguments() != null &&
                workflow.getSpec().getArguments().getParameters() != null) {
            for (Parameter p : workflow.getSpec().getArguments().getParameters()) {
                if (p.getValue() != null) workflowParams.put(p.getName(), p.getValue());
            }
        }

        Map<String, Template> templateMap = new LinkedHashMap<>();
        for (Template t : workflow.getSpec().getTemplates()) {
            templateMap.put(t.getName(), t);
        }
        log.debug("Templates: {}", templateMap.keySet());

        Template entrypointTemplate = templateMap.get(entrypointName);

        ExecutorService threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "argo-executor");
            t.setDaemon(true);
            return t;
        });

        ExecutionContext ctx = new ExecutionContext(templateMap, workflowParams, threadPool);

        WorkflowNode root = WorkflowNode.from(entrypointName, entrypointTemplate, templateMap, Set.of());
        CompletableFuture<Void> future = root.executeAsync(ctx, Map.of())
                .thenAccept(_ -> {})
                .whenComplete((_, _) -> threadPool.shutdown());

        return new WorkflowRun(root, future);
    }

    public WorkflowRun execute() throws Exception {
        return executeAsync().await();
    }
}
