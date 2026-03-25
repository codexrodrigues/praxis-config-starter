/**
 * Controllers REST canônicos do config-starter.
 *
 * <p>
 * Este pacote concentra as superfícies públicas de configuração e AI da plataforma, incluindo
 * persistência de preferências em {@code /api/praxis/config/ui}, orquestração AI em
 * {@code /api/praxis/config/ai/**} e ingestão de catálogos em
 * {@code /api/praxis/config/api-catalog/**}.
 * </p>
 *
 * <p>
 * Como essas rotas são consumidas por hosts, UIs metadata-driven e fluxos operacionais internos,
 * a documentação de classe e método aqui deve priorizar contrato HTTP, headers obrigatórios,
 * semântica de escopo, códigos de resposta e precedência entre payload e cabeçalhos.
 * </p>
 */
package org.praxisplatform.config.controller;
