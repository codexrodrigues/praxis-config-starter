package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Internal, derived compatibility projection for declared manifest operations. It is deliberately
 * not a second manifest or a public contract: every node and relation can be recomputed from the
 * current manifest version.
 */
final class AgenticAuthoringComponentOperationCompatibilityGraph {

    private final Map<String, Node> nodes;
    private final Set<Relation> relations;

    private AgenticAuthoringComponentOperationCompatibilityGraph(Map<String, Node> nodes, Set<Relation> relations) {
        this.nodes = Map.copyOf(nodes);
        this.relations = Set.copyOf(relations);
    }

    static AgenticAuthoringComponentOperationCompatibilityGraph derive(JsonNode manifest) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (JsonNode operation : manifest.path("operations")) {
            String id = operation.path("operationId").asText("");
            if (!id.isBlank()) nodes.put(id, Node.from(operation));
        }
        Set<Relation> relations = new LinkedHashSet<>();
        for (Node left : nodes.values()) for (Node right : nodes.values()) {
            if (left == right) continue;
            if (left.creates(right)) {
                relations.add(new Relation(left.operationId, right.operationId, RelationKind.CREATES_TARGET));
                if (right.requiresExistingTarget()) relations.add(new Relation(left.operationId, right.operationId, RelationKind.MUST_RUN_BEFORE));
            }
            if (left.removes(right)) relations.add(new Relation(left.operationId, right.operationId, RelationKind.REMOVES_TARGET));
            if (left.mutates(right)) relations.add(new Relation(left.operationId, right.operationId, RelationKind.MUTATES_TARGET));
            if (left.removes(right) && right.mutates(right)) relations.add(new Relation(left.operationId, right.operationId, RelationKind.POTENTIAL_CONFLICT));
            if (independentlyComposable(left, right)) relations.add(new Relation(left.operationId, right.operationId, RelationKind.CAN_COMPOSE));
            if (!independentlyComposable(left, right) && !definitivelyConflicts(left, right)) relations.add(new Relation(left.operationId, right.operationId, RelationKind.REQUIRES_COMPILER_VALIDATION));
        }
        return new AgenticAuthoringComponentOperationCompatibilityGraph(nodes, relations);
    }

    Resolution resolve(List<String> requestedOperationIds) {
        if (requestedOperationIds == null || requestedOperationIds.isEmpty()) return Resolution.reject("component-operation-selection-invalid-operation-ids");
        List<Node> requested = new ArrayList<>();
        for (String id : requestedOperationIds) {
            Node node = nodes.get(id);
            if (node == null) return Resolution.reject("component-operation-selection-invalid-operation-ids");
            requested.add(node);
        }
        List<String> conflicts = conflicts(requested);
        if (!conflicts.isEmpty()) return Resolution.reject("component-operation-compatibility-conflict:" + String.join(",", conflicts));

        // The graph is allowed to expose only one adjacent layer. It never invents an operation:
        // an auxiliary operation is included only when it is a declared creator for a selected
        // target-exists consumer and no selected operation already creates that target kind.
        LinkedHashSet<Node> expanded = new LinkedHashSet<>(requested);
        for (Node consumer : requested) {
            if (!consumer.requiresExistingTarget()) continue;
            boolean satisfied = requested.stream().anyMatch(candidate -> candidate.creates(consumer));
            if (satisfied) continue;
            List<Node> creators = nodes.values().stream()
                    .filter(candidate -> candidate.creates(consumer))
                    .filter(candidate -> compatible(candidate, consumer))
                    .toList();
            if (creators.size() == 1) expanded.add(creators.get(0));
        }
        List<Node> ordered = new ArrayList<>(expanded);
        ordered.sort(Comparator.comparingInt((Node node) -> orderBeforeCount(node, expanded)).reversed()
                .thenComparing(Node::operationId));
        return Resolution.accept(ordered.stream().map(Node::operationId).toList());
    }

    private int orderBeforeCount(Node candidate, Set<Node> all) {
        return (int) all.stream().filter(other -> candidate.creates(other) && other.requiresExistingTarget()).count();
    }

    private List<String> conflicts(List<Node> selected) {
        List<String> conflicts = new ArrayList<>();
        for (int left = 0; left < selected.size(); left++) for (int right = left + 1; right < selected.size(); right++) {
            if (!compatible(selected.get(left), selected.get(right))) conflicts.add(selected.get(left).operationId + "~" + selected.get(right).operationId);
        }
        return conflicts;
    }

    private boolean compatible(Node left, Node right) {
        return !definitivelyConflicts(left, right);
    }

    private static boolean definitivelyConflicts(Node left, Node right) {
        return (left.removes(right) && right.mutates(right))
                || (right.removes(left) && left.mutates(left))
                || (left.effectKinds.contains("set-value") && right.effectKinds.contains("set-value") && overlaps(left.affectedPaths, right.affectedPaths));
    }

    private static boolean independentlyComposable(Node left, Node right) {
        return !overlaps(left.affectedPaths, right.affectedPaths)
                || (left.effectKinds.contains("merge-object") && right.effectKinds.contains("merge-object") && !overlaps(left.affectedPaths, right.affectedPaths));
    }

    private static boolean overlaps(Set<String> left, Set<String> right) {
        for (String a : left) for (String b : right) if (a.equals(b) || a.startsWith(b + ".") || b.startsWith(a + ".")) return true;
        return false;
    }

    record Resolution(boolean accepted, List<String> operationIds, String reason) {
        static Resolution accept(List<String> operationIds) { return new Resolution(true, List.copyOf(operationIds), ""); }
        static Resolution reject(String reason) { return new Resolution(false, List.of(), reason); }
    }

    private enum RelationKind { CREATES_TARGET, REMOVES_TARGET, MUTATES_TARGET, MUST_RUN_BEFORE, POTENTIAL_CONFLICT, CAN_COMPOSE, REQUIRES_COMPILER_VALIDATION }
    private record Relation(String fromOperationId, String toOperationId, RelationKind kind) { }

    private record Node(String operationId, String targetKind, Set<String> effectKinds, Set<String> affectedPaths,
                        Set<String> preconditions, Set<String> validators, boolean destructive, String submissionImpact) {
        static Node from(JsonNode operation) {
            Set<String> effects = texts(operation.path("effects"), "kind");
            Set<String> paths = new LinkedHashSet<>();
            operation.path("affectedPaths").forEach(path -> paths.add(normalizePath(path.asText(""))));
            operation.path("effects").forEach(effect -> paths.add(normalizePath(effect.path("path").asText(""))));
            paths.remove("");
            return new Node(operation.path("operationId").asText(""), operation.path("target").path("kind").asText(operation.path("targetKind").asText("")),
                    effects, Set.copyOf(paths), texts(operation.path("preconditions"), null), texts(operation.path("validators"), null),
                    operation.path("destructive").asBoolean(false), operation.path("submissionImpact").asText(""));
        }
        boolean creates(Node other) { return effectKinds.contains("append-unique") && sharesTargetRoot(other); }
        boolean removes(Node other) { return effectKinds.contains("remove-by-key") && sharesTargetRoot(other); }
        boolean mutates(Node other) { return sharesTargetRoot(other) && !effectKinds.contains("append-unique") && !effectKinds.contains("remove-by-key"); }
        boolean requiresExistingTarget() { return preconditions.contains("target-exists"); }
        private boolean sharesTargetRoot(Node other) {
            for (String path : affectedPaths) for (String otherPath : other.affectedPaths) {
                if (root(path).equals(root(otherPath))) return true;
            }
            return Objects.equals(targetKind, other.targetKind) && !targetKind.isBlank();
        }
        private static String root(String path) { int dot = path.indexOf('.'); return dot < 0 ? path : path.substring(0, dot); }
        private static Set<String> texts(JsonNode values, String field) {
            Set<String> result = new LinkedHashSet<>();
            values.forEach(value -> result.add(field == null ? value.asText("") : value.path(field).asText("")));
            result.remove(""); return Set.copyOf(result);
        }
        private static String normalizePath(String path) { return path == null ? "" : path.replace("[]", ""); }
    }
}
