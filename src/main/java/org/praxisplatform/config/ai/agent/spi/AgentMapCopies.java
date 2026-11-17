package org.praxisplatform.config.ai.agent.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utilitario de copias defensivas imutaveis tolerantes a valores {@code null} e preservadoras da
 * ordem de insercao para payloads estruturados e argumentos de ferramentas do agente operacional.
 */
public final class AgentMapCopies {

    private AgentMapCopies() {
    }

    public static <V> Map<String, V> immutableOrderedMap(Map<String, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, V> copy = new LinkedHashMap<>();
        for (Map.Entry<String, V> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
    }

    public static <T> List<T> immutableList(List<T> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<T> copy = new ArrayList<>(source.size());
        for (T item : source) {
            if (item != null) {
                copy.add(item);
            }
        }
        return copy.isEmpty() ? List.of() : Collections.unmodifiableList(copy);
    }
}
