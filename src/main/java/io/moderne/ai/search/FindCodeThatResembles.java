package io.moderne.ai.search;

import io.moderne.ai.EmbeddingModelClient;
import io.moderne.ai.table.EmbeddingPerformance;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.openrewrite.*;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Objects.requireNonNull;

@RequiredArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class FindCodeThatResembles extends Recipe {
    @Option(displayName = "Resembles",
            description = "The text, either a natural language description or a code sample, " +
                          "that you are looking for.",
            example = "HTTP request with Content-Type application/json")
    private final String resembles;

    @Option(displayName = "Method filters",
            description = "Since AI based matching has a higher latency than rules based matching, " +
                          "filter the methods that are searched for the `resembles` text.",
            example = "kong.unirest.* *(..)")
    private final List<String> methodFilters;

    @Option(displayName = "Hugging Face token",
            description = "The token to use for the HuggingFace API. Create a " +
                          "[read token](https://huggingface.co/settings/tokens).",
            example = "hf_*****")
    private final String huggingFaceToken;

    private transient EmbeddingModelClient modelClient;
    private final transient EmbeddingPerformance performance = new EmbeddingPerformance(this);

    @Override
    public String getDisplayName() {
        return "Find HTTP requests with a particular `Content-Type` header";
    }

    @Override
    public String getDescription() {
        return "This recipe uses a hybrid rules-based and AI approach to find " +
               "HTTP requests with a particular `Content-Type` header.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        List<MethodMatcher> methodMatchers = new ArrayList<>(methodFilters.size());
        for (String m : methodFilters) {
            methodMatchers.add(new MethodMatcher(m, true));
        }

        List<TreeVisitor<?, ExecutionContext>> preconditions = new ArrayList<>(methodMatchers.size());
        for (MethodMatcher m : methodMatchers) {
            preconditions.add(new UsesMethod<>(m));
        }

        //noinspection unchecked
        return Preconditions.check(Preconditions.or(preconditions.toArray(new TreeVisitor[0])), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    getCursor().putMessage("count", new AtomicInteger());
                    getCursor().putMessage("max", new AtomicLong());
                    getCursor().putMessage("histogram", new EmbeddingPerformance.Histogram());
                    J visit = super.visit(tree, ctx);
                    if (getCursor().getMessage("count", new AtomicInteger()).get() > 0) {
                        Duration max = Duration.ofNanos(requireNonNull(getCursor().<AtomicLong>getMessage("max")).get());
                        performance.insertRow(ctx, new EmbeddingPerformance.Row((
                                (SourceFile) tree).getSourcePath().toString(),
                                requireNonNull(getCursor().<AtomicInteger>getMessage("count")).get(),
                                requireNonNull(getCursor().<EmbeddingPerformance.Histogram>getMessage("histogram")).getBuckets(),
                                max));
                    }
                    return visit;
                } else {
                    return super.visit(tree, ctx);
                }
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                boolean matches = false;
                for (MethodMatcher methodMatcher : methodMatchers) {
                    if (methodMatcher.matches(method)) {
                        matches = true;
                    }
                }
                if (!matches) {
                    return super.visitMethodInvocation(method, ctx);
                }

                if (modelClient == null) {
                    modelClient = new EmbeddingModelClient(huggingFaceToken);
                    modelClient.start();
                }

                EmbeddingModelClient.Relatedness related = modelClient.getRelatedness(resembles,
                        method.printTrimmed(getCursor()));
                for (Duration timing : related.getEmbeddingTimings()) {
                    requireNonNull(getCursor().<AtomicInteger>getNearestMessage("count")).incrementAndGet();
                    requireNonNull(getCursor().<EmbeddingPerformance.Histogram>getNearestMessage("histogram")).add(timing);
                    AtomicLong max = getCursor().getNearestMessage("max");
                    if (requireNonNull(max).get() < timing.toNanos()) {
                        max.set(timing.toNanos());
                    }
                }
                return related.isRelated() ?
                        SearchResult.found(method) :
                        super.visitMethodInvocation(method, ctx);
            }
        });
    }
}
