package org.praxisplatform.config.service;

/**
 * Extensao de autorizacao para mutacoes de configuracao de UI.
 *
 * <p>Hosts corporativos devem registrar esta policy para proteger configuracoes
 * governadas, por exemplo exigindo {@code ui.authoring.configure} para uma
 * tabela authorable. A implementacao deve consultar o principal autenticado e
 * a empresa resolvida pelo servidor, nunca uma capability enviada pelo cliente.
 */
@FunctionalInterface
public interface UiConfigWriteAuthorizer {

    void authorize(UiConfigWriteAuthorizationRequest request);
}
