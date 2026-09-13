package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.repository.DomainRuleMaterializationRepository;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Authoritative embedded Config reader. It never grants domain authorization or evaluates business records. */
public final class OperationalPolicyService {
    private static final JsonMapper JSON = new JsonMapper();
    private static final int MAX_HISTORY_ROWS = 4096;
    private final DomainRuleMaterializationRepository materializations;
    private final DomainRuleService projections;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OperationalPolicyService(DomainRuleMaterializationRepository materializations, DomainRuleService projections,
            PlatformTransactionManager configTransactionManager, Clock clock) {
        this.materializations = Objects.requireNonNull(materializations);
        this.projections = Objects.requireNonNull(projections);
        this.clock = Objects.requireNonNull(clock);
        transaction = new TransactionTemplate(Objects.requireNonNull(configTransactionManager));
        transaction.setReadOnly(true);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public OperationalPolicyResolution resolveOperationalPolicy(OperationalPolicyTarget target, DomainRuleGovernancePrincipal resolvedPrincipal) {
        Objects.requireNonNull(target, "target");
        DomainRuleLifecycleScope.lockKey(resolvedPrincipal); // Validate resolved identity; no write lock for readers.
        String tenant = resolvedPrincipal.tenantId().trim();
        String environment = resolvedPrincipal.environment().trim();
        try {
            List<String> rows = transaction.execute(status -> materializations.operationalSnapshot(tenant, environment,
                    target.targetLayer(), target.targetArtifactType(), target.targetArtifactKey()));
            if (rows == null || rows.size() > MAX_HISTORY_ROWS) return unavailable(target, tenant, environment);
            return resolve(target, tenant, environment, rows);
        } catch (DataAccessException | TransactionException | IllegalArgumentException
                | org.praxisplatform.config.exception.ConfigurationIngestionException ex) {
            return unavailable(target, tenant, environment);
        }
    }

    private OperationalPolicyResolution resolve(OperationalPolicyTarget target, String tenant, String environment, List<String> rows) {
        List<JsonNode> evidence = new ArrayList<>();
        OperationalPolicyResolution.Policy head = null;
        boolean inconsistent = false;
        for (String row : rows) {
            try {
                if (row == null || row.length() > 8 * 1024 * 1024) return unavailable(target, tenant, environment);
                JsonNode item = DomainRuleMaterializationFingerprint.parse(row);
                evidence.add(item);
                if ("event".equals(item.path("kind").asText())) {
                    inconsistent |= item.path("uncertainLink").asBoolean(true) || !item.path("definitionScopeValid").asBoolean();
                    continue;
                }
                if (!"materialization".equals(item.path("kind").asText()) || !item.path("everApplied").isBoolean()
                        || !item.path("everApplied").booleanValue()) {
                    inconsistent = true;
                    continue;
                }
                if (!"applied".equals(item.path("status").asText())) continue;
                if (head != null) { inconsistent = true; continue; }
                head = validatedPolicy(item, target, tenant, environment);
            } catch (IllegalArgumentException | org.praxisplatform.config.exception.ConfigurationIngestionException ex) {
                inconsistent = true;
            }
        }
        var state = inconsistent ? OperationalPolicyResolution.State.INCONSISTENT_OR_UNAVAILABLE
                : head != null ? OperationalPolicyResolution.State.ELIGIBLE_APPLIED_HEAD
                : evidence.isEmpty() ? OperationalPolicyResolution.State.NEVER_APPLIED
                : OperationalPolicyResolution.State.PREVIOUSLY_APPLIED_WITHOUT_ELIGIBLE_HEAD;
        return result(target, tenant, environment, state, inconsistent ? null : head, evidence);
    }

    private OperationalPolicyResolution.Policy validatedPolicy(JsonNode item, OperationalPolicyTarget target, String tenant, String environment) {
        JsonNode source = item.path("definition");
        if (!"active".equals(source.path("status").asText()) || !tenant.equals(source.path("tenantId").asText())
                || !environment.equals(source.path("environment").asText())
                || !item.path("definitionId").asText().equals(source.path("id").asText())) throw new IllegalArgumentException();
        var definition = DomainRuleDefinition.builder().id(UUID.fromString(source.path("id").asText()))
                .tenantId(tenant).environment(environment).ruleKey(source.path("ruleKey").asText())
                .version(source.path("version").intValue()).ruleType(source.path("ruleType").asText())
                .resourceKey(text(source, "resourceKey")).serviceKey(text(source, "serviceKey")).contextKey(text(source, "contextKey"))
                .definition(source.path("definition").toString()).parameters(source.path("parameters").toString())
                .condition(source.path("condition").isNull() ? null : source.path("condition").toString())
                .governance(source.path("governance").toString()).build();
        JsonNode payload = item.path("payload");
        var effect = OperationalPolicyContract.validate(definition, target, payload);
        JsonNode expected = projections.operationalProjection(definition, target);
        if (!PraxisCanonicalJson.canonicalize(payload).equals(PraxisCanonicalJson.canonicalize(expected))) throw new IllegalArgumentException();
        String hash = DomainRuleMaterializationFingerprint.sha256(definition, target.targetLayer(), target.targetArtifactType(),
                target.targetArtifactKey(), text(item, "targetPointer"), text(item, "materializedRuleId"), payload);
        if (!hash.equals(text(item, "sourceHash"))) throw new IllegalArgumentException();
        return new OperationalPolicyResolution.Policy(UUID.fromString(item.path("id").asText()), definition.getId(),
                definition.getVersion(), hash, effect, payload);
    }

    private OperationalPolicyResolution unavailable(OperationalPolicyTarget target, String tenant, String environment) {
        return result(target, tenant, environment, OperationalPolicyResolution.State.INCONSISTENT_OR_UNAVAILABLE, null, List.of());
    }

    private OperationalPolicyResolution result(OperationalPolicyTarget target, String tenant, String environment,
            OperationalPolicyResolution.State state, OperationalPolicyResolution.Policy policy, List<JsonNode> evidence) {
        ObjectNode fingerprint = JSON.createObjectNode();
        fingerprint.put("format", "praxis.config.operational-policy/1");
        fingerprint.put("tenantId", tenant); fingerprint.put("environment", environment);
        fingerprint.put("layer", target.targetLayer()); fingerprint.put("type", target.targetArtifactType());
        fingerprint.put("key", target.targetArtifactKey()); fingerprint.put("state", state.name());
        var rows = fingerprint.putArray("evidence");
        evidence.stream().sorted(java.util.Comparator.comparing(item -> item.path("kind").asText() + ":" + item.path("id").asText()))
                .forEach(item -> {
                    ObjectNode identity = rows.addObject();
                    for (String field : List.of("kind", "id", "revision", "status", "everApplied", "sourceHash",
                            "definitionId", "uncertainLink", "definitionScopeValid")) {
                        if (item.has(field)) identity.set(field, item.get(field));
                    }
                });
        return new OperationalPolicyResolution(state, target, PraxisCanonicalJson.sha256(fingerprint), policy, clock.instant());
    }

    private static String text(JsonNode node, String field) { return node.path(field).isNull() || node.path(field).isMissingNode() ? null : node.path(field).asText(); }
}
