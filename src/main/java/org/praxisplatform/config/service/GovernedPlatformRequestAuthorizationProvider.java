package org.praxisplatform.config.service;

import java.util.Optional;

/**
 * SPI de host para autorizar leituras HTTP governadas usadas no grounding.
 *
 * <p>O Config Starter nao conhece cookies, JWTs ou o IAM do host. Um host pode implementar este
 * contrato para trocar a identidade server-side resolvida por uma credencial restrita ao destino.
 * Implementacoes devem validar {@link GovernedPlatformRequest#targetUri()} antes de emitir qualquer
 * valor. A ausencia de provider ou de credencial mantem a chamada sem autorizacao e faz endpoints
 * protegidos falharem fechados.</p>
 */
@FunctionalInterface
public interface GovernedPlatformRequestAuthorizationProvider {

    /** Retorna o valor completo do header Authorization, sem expor a credencial ao fluxo AI. */
    Optional<String> authorizationHeader(GovernedPlatformRequest request);

    static GovernedPlatformRequestAuthorizationProvider none() {
        return request -> Optional.empty();
    }
}
