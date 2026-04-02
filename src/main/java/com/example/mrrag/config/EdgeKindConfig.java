package com.example.mrrag.config;

import com.example.mrrag.service.AstGraphService.EdgeKind;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Reads per-edge-kind enable/disable flags from {@code application.yml}.
 *
 * <p>Each flag follows the pattern:
 * <pre>graph.edge.&lt;EDGE_KIND&gt;.enabled: true</pre>
 *
 * <p>If a property is not defined, the default is {@code true} (all edges enabled).
 *
 * <p>Usage example:
 * <pre>{@code
 * if (edgeConfig.isEnabled(EdgeKind.INSTANTIATES)) {
 *     // add edge
 * }
 * }</pre>
 */
@Component
public class EdgeKindConfig {

    private final Environment env;

    public EdgeKindConfig(Environment env) {
        this.env = env;
    }

    /**
     * Returns {@code true} if the given edge kind is enabled.
     * Defaults to {@code true} when the property is absent.
     *
     * @param kind the edge kind to check
     * @return whether edges of this kind should be added to the graph
     */
    public boolean isEnabled(EdgeKind kind) {
        return env.getProperty(
                "graph.edge." + kind.name() + ".enabled",
                Boolean.class,
                true
        );
    }
}
