package com.example.mrrag.config;

import com.example.mrrag.graph.GraphRawBuilder;
import com.example.mrrag.service.AstGraphService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Конфигурация включения/отключения типов рёбер графа.
 *
 * <p>Читает свойства вида {@code graph.edge.<NAME>.enabled}
 * из {@code application.properties} (или любого другого
 * источника Spring Environment). Если свойство не задано,
 * ребро считается <strong>включённым</strong> по умолчанию.
 *
 * <p>Пример в {@code application.properties}:
 * <pre>
 * graph.edge.READS_LOCAL_VAR.enabled=false
 * graph.edge.WRITES_LOCAL_VAR.enabled=false
 * </pre>
 *
 * <p>Типичное использование в сервисе:
 * <pre>{@code
 * if (edgeKindConfig.isEnabled(EdgeKind.INSTANTIATES)) {
 *     graph.addEdge(caller, EdgeKind.INSTANTIATES, callee);
 * }
 * }</pre>
 *
 * @see GraphRawBuilder.EdgeKind
 */
@Component
public class EdgeKindConfig {

    private final Environment env;

    /**
     * Создаёт конфигурацию, использующую переданный {@link Environment}
     * для чтения свойств.
     *
     * @param env Spring-окружение; не должен быть {@code null}
     */
    public EdgeKindConfig(Environment env) {
        this.env = env;
    }

    /**
     * Проверяет, разрешено ли добавлять рёбра данного типа в граф.
     *
     * <p>Читает свойство {@code graph.edge.<KIND_NAME>.enabled}.
     * Если свойство отсутствует, возвращает {@code true}.
     *
     * @param kind тип ребра; не должен быть {@code null}
     * @return {@code true}, если ребро включено; {@code false} иначе
     */
    public boolean isEnabled(GraphRawBuilder.EdgeKind kind) {
        return env.getProperty(
                "graph.edge." + kind.name() + ".enabled",
                Boolean.class,
                true
        );
    }
}