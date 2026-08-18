package org.praxisplatform.config.dto;

import java.util.List;
import java.util.Map;

/** Safe, governed description of one fact accepted by a domain decision. */
public record DomainRuleFactDescriptor(
        String path,
        String valueType,
        boolean nullable,
        Map<String, String> labels,
        Map<String, String> descriptions,
        String providerRef,
        List<String> evidenceRefs,
        String sensitivity,
        String redaction
) {
}
