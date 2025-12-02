package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.domain.UiConfiguration;
import org.praxisplatform.config.repository.UiConfigurationRepository;
import org.praxisplatform.config.util.JsonMerger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UiConfigurationService {

    private final UiConfigurationRepository repository;
    private final JsonMerger jsonMerger;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public JsonNode getResolvedConfig(String tenantId, String appId, String componentId, String userId) {
        // 1. Fetch SYSTEM config (Base)
        Optional<UiConfiguration> systemConfigOpt = repository.findByTenantIdAndAppIdAndComponentIdAndScopeAndScopeKey(
                tenantId, appId, componentId, Scope.SYSTEM, "GLOBAL"); // Assuming GLOBAL for generic system config or null handling

        // If we don't have a system config, we might return empty or throw.
        // For flexibility, let's start with an empty object if missing, or the actual config.
        JsonNode baseNode = objectMapper.createObjectNode();
        if (systemConfigOpt.isPresent()) {
            try {
                baseNode = objectMapper.readTree(systemConfigOpt.get().getConfigJson());
            } catch (Exception e) {
                log.error("Error parsing SYSTEM config for component {}", componentId, e);
            }
        }

        // 2. Fetch USER config (Override) if userId is present
        if (userId != null) {
            Optional<UiConfiguration> userConfigOpt = repository.findByTenantIdAndAppIdAndComponentIdAndScopeAndScopeKey(
                    tenantId, appId, componentId, Scope.USER, userId);

            if (userConfigOpt.isPresent()) {
                try {
                    JsonNode userNode = objectMapper.readTree(userConfigOpt.get().getConfigJson());
                    // 3. Merge
                    baseNode = jsonMerger.merge(baseNode, userNode);
                } catch (Exception e) {
                    log.error("Error parsing USER config for component {} user {}", componentId, userId, e);
                }
            }
        }

        return baseNode;
    }

    @Transactional(readOnly = true)
    public Optional<UiConfiguration> getSystemConfig(String tenantId, String appId, String componentId) {
        return repository.findByTenantIdAndAppIdAndComponentIdAndScopeAndScopeKey(
                tenantId, appId, componentId, Scope.SYSTEM, "GLOBAL");
    }

    @Transactional
    public UiConfiguration saveConfig(UiConfiguration config) {
        // Check if exists to update or create new
        Optional<UiConfiguration> existing = repository.findByTenantIdAndAppIdAndComponentIdAndScopeAndScopeKey(
                config.getTenantId(), config.getAppId(), config.getComponentId(), config.getScope(), config.getScopeKey());

        if (existing.isPresent()) {
            UiConfiguration dbConfig = existing.get();
            dbConfig.setConfigJson(config.getConfigJson());
            dbConfig.setResourcePath(config.getResourcePath());
            dbConfig.setAiDescription(config.getAiDescription());
            return repository.save(dbConfig);
        } else {
            return repository.save(config);
        }
    }
}
