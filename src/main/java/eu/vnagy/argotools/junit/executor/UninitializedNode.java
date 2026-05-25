package eu.vnagy.argotools.junit.executor;

import eu.vnagy.argotools.junit.model.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A node at a recursion boundary: the template is known but the subtree cannot be built
 * at construction time without causing infinite recursion. Expands lazily on first execution.
 */
public final class UninitializedNode implements WorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(UninitializedNode.class);

    private final String name;
    private final Template template;
    private volatile WorkflowNode resolved;
    private volatile boolean skipped;
    private volatile boolean omitted;

    UninitializedNode(String name, Template template) {
        this.name = name;
        this.template = template;
    }

    /** The expanded plan node, or {@code null} if not yet executed. */
    public WorkflowNode resolved() { return resolved; }

    @Override public String name()       { return name; }
    @Override public boolean succeeded() { return resolved != null && resolved.succeeded(); }
    @Override public boolean failed()    { return resolved != null && resolved.failed(); }
    @Override public boolean errored()   { return resolved != null && resolved.errored(); }
    @Override public boolean skipped()   { return skipped || (resolved != null && resolved.skipped()); }
    @Override public boolean omitted()   { return omitted || (resolved != null && resolved.omitted()); }
    @Override public boolean daemoned()  { return resolved != null && resolved.daemoned(); }
    @Override public boolean running()   { return resolved != null && resolved.running(); }
    @Override public boolean pending()   { return !skipped && !omitted && resolved == null; }

    @Override public void skip() { this.skipped = true; }
    @Override public void omit() { this.omitted = true; }

    @Override
    public CompletableFuture<WorkflowNode> executeAsync(ExecutionContext ctx, Map<String, String> inputParams) {
        log.debug("UninitializedNode '{}': expanding template '{}'", name, template.getName());
        WorkflowNode planNode = WorkflowNode.from(name, template, ctx.templateMap, Set.of(template.getName()));
        this.resolved = planNode;
        return planNode.executeAsync(ctx, inputParams);
    }
}
