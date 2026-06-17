package org.praxisplatform.config.ai.authoring;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AgenticAuthoringPresentationText {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private AgenticAuthoringPresentationText() {
    }

    public static String display(String value) {
        String text = repairMojibake(safe(value));
        if (text.isBlank()) {
            return "";
        }
        return text
                .replace(" analises", " análises")
                .replace("Analises", "Análises")
                .replace(" analitica", " analítica")
                .replace("Analitica", "Analítica")
                .replace(" graficos", " gráficos")
                .replace("Graficos", "Gráficos")
                .replace(" paineis", " painéis")
                .replace("Paineis", "Painéis")
                .replace(" formularios", " formulários")
                .replace("Formularios", "Formulários")
                .replace(" codigo", " código")
                .replace("Codigo", "Código")
                .replace(" opcoes", " opções")
                .replace("Opcoes", "Opções")
                .replace(" opcao", " opção")
                .replace("Opcao", "Opção")
                .replace(" colecao", " coleção")
                .replace("Colecao", "Coleção")
                .replace(" paginacao", " paginação")
                .replace("Paginacao", "Paginação")
                .replace(" operacao", " operação")
                .replace("Operacao", "Operação")
                .replace(" operacoes", " operações")
                .replace("Operacoes", "Operações")
                .replace(" execucao", " execução")
                .replace("Execucao", "Execução")
                .replace(" exibicao", " exibição")
                .replace("Exibicao", "Exibição")
                .replace(" posicao", " posição")
                .replace("Posicao", "Posição")
                .replace(" tendencia", " tendência")
                .replace("Tendencia", "Tendência")
                .replace(" catalogo", " catálogo")
                .replace("Catalogo", "Catálogo")
                .replace(" competencia", " competência")
                .replace("Competencia", "Competência")
                .replace(" funcionarios", " funcionários")
                .replace("Funcionarios", "Funcionários")
                .replace(" responsaveis", " responsáveis")
                .replace("Responsaveis", "Responsáveis")
                .replace(" responsavel", " responsável")
                .replace("Responsavel", "Responsável")
                .replace(" referencias", " referências")
                .replace("Referencias", "Referências")
                .replace(" relatorios", " relatórios")
                .replace("Relatorios", "Relatórios")
                .replace(" estavel", " estável")
                .replace("Estavel", "Estável")
                .replace(" designacao", " designação")
                .replace("Designacao", "Designação")
                .replace(" lotacao", " lotação")
                .replace("Lotacao", "Lotação")
                .replace(" aprovacoes", " aprovações")
                .replace("Aprovacoes", "Aprovações");
    }

    public static String assistantReply(String value) {
        String text = display(value);
        if (text.isBlank()) {
            return "";
        }
        String protectedText = protectCanonicalConfigPaths(text);
        String cleaned = protectedText
                .replaceAll("(?i)\\bsource\\s+declarad[oa]\\b", "fonte declarada")
                .replaceAll("(?i)\\bsurface\\s+declarad[oa]\\b", "opção declarada")
                .replaceAll("(?i)\\bsurface\\s+\\\"detail\\\"", "visão de detalhe")
                .replaceAll("(?i)\\bresource-surface\\b", "visão por recurso")
                .replaceAll("(?i)\\bpraxis-table\\b", "tabela")
                .replaceAll("(?i)\\btarget\\b", "destino")
                .replaceAll("(?i)\\bDetail\\b", "Detalhe")
                .replaceAll("(?i)\\bRecurso associado\\b", "Fonte associada")
                .replaceAll("(?i)\\btarget\\.resourcePath\\s*:", "fonte:")
                .replaceAll("(?i)\\bresourcePath\\s*:", "fonte:")
                .replaceAll("(?i)\\brecord\\.related-surface\\b", "visão relacionada por registro")
                .replaceAll("(?i)\\bVIEW\\s*,\\s*scope\\s+ITEM\\b", "visualização por registro")
                .replaceAll("(?i)\\bVIEW\\s+por\\s+item\\b", "visualização por item")
                .replaceAll("(?i)\\bscope\\s+ITEM\\b", "por registro")
                .replaceAll("(?i)\\bcanonicalOperations\\.create\\s*=\\s*false\\b",
                        "criação direta não publicada nesta visão")
                .replaceAll("(?i)\\bcanonicalOperations\\.create\\s*=\\s*true\\b",
                        "criação direta publicada nesta visão")
                .replaceAll("(?i)\\bcanonicalOperations\\.[a-zA-Z0-9_]+\\s*=\\s*(true|false)\\b",
                        "capacidade governada declarada")
                .replaceAll("(?i)\\bendpoint(s)?\\b", "fonte")
                .replaceAll("(?i)\\bschema(s)?\\b", "campos confirmados")
                .replaceAll("(?i)\\bresourceKey\\b", "fonte")
                .replaceAll("(?i)\\bsubmitUrl\\b", "forma de envio")
                .replaceAll("(?i)\\bsourceRefs\\b", "fontes confirmadas")
                .replaceAll("(?i)\\brecordSurfaces\\b", "visões disponíveis")
                .replaceAll("(?i)\\bconsultativeContext\\b", "contexto confirmado")
                .replaceAll("(?i)\\bResourcePath\\s*/\\s*operação\\s*:", "Fonte:")
                .replaceAll("(?i)\\bResourcePath\\s+alvo\\s*:", "Fonte:")
                .replaceAll("(?i)\\bResourcePath\\b", "fonte")
                .replaceAll("(?i)\\bSemantic intent\\s*:", "Intenção:")
                .replaceAll("(?i)\\bLabel\\s*:", "Nome:")
                .replaceAll("(?i)\\brecord-related\\s+visões\\s+relacionadas\\b",
                        "visões relacionadas ao registro")
                .replaceAll("(?i)\\brecord-related\\b", "relacionadas ao registro")
                .replaceAll("(?i)\\bscope\\s*:\\s*ITEM\\b", "por registro")
                .replaceAll("(?i)\\bstatsGroupBy\\b", "agrupamento")
                .replaceAll("(?i)\\bstatsDistribution\\b", "distribuição")
                .replaceAll("(?i)\\bstatsTimeSeries\\b", "série temporal")
                .replaceAll("(?i)\\boptionSources\\b", "listas de opções")
                .replaceAll("(?i)\\bcursor/client\\b", "paginação remota ou local")
                .replaceAll(
                        "(?i)(?<![\\p{L}\\p{N}_-])(?:/?api/|schemas/|operations/|human-resources/|risk-intelligence/|assets/|procurement/)[a-z0-9][a-z0-9-]*(?:/[a-z0-9][a-z0-9-]*)*\\b",
                        "fonte confirmada")
                .replaceAll("(?i)\\bcreate\\b", "criação")
                .replaceAll("(?i)\\bdelete\\b", "exclusão")
                .replaceAll("(?i)\\bcriação\\s*=\\s*false\\b", "criação desabilitada")
                .replaceAll("(?i)\\bcriação\\s*=\\s*true\\b", "criação habilitada")
                .replaceAll("(?i)\\bsurfaces\\b", "visões relacionadas")
                .replaceAll("(?i)\\bsurface\\b", "visão relacionada")
                .replaceAll("(?i)\\bsuperficies\\b", "visões")
                .replaceAll("(?i)\\bsuperfícies\\s+baseadas\\b", "visões baseadas")
                .replaceAll("(?i)\\bsuperfícies\\s+tabela\\b", "visões em tabela")
                .replaceAll("(?i)\\bsuperfícies\\s+relacionadas\\b", "visões relacionadas")
                .replaceAll("(?i)\\bsuperfície\\s+relacionada\\b", "visão relacionada")
                .replaceAll("(?i)\\bsuperfície\\s+de\\s+detalhe\\b", "visão de detalhe")
                .replaceAll("(?i)\\bsuperfície\\s+de\\s+visualização\\b", "visão de detalhe")
                .replaceAll("(?i)\\bsuperfície\\b", "visão")
                .replaceAll("(?i)\\bsuperfícies\\b", "visões")
                .replaceAll("(?i)tabelas/visões relacionadas", "tabelas e visões relacionadas")
                .replaceAll("(?i)visões relacionadas relacionadas", "visões relacionadas")
                .replaceAll("(?i)visão relacionada relacionada", "visão relacionada")
                .replaceAll("(?i)superfícies já declaradas", "opções disponíveis")
                .replaceAll("(?i)superfícies declaradas", "opções disponíveis")
                .replaceAll("(?i)qual superfície", "qual opção")
                .replaceAll("(?i)Relação:\\s*visão relacionada de recurso/visão",
                        "Relação: visão de detalhe do recurso")
                .replaceAll("(?i)Escopo:\\s*registro\\s*\\(ITEM\\)", "Escopo: registro")
                .replaceAll("\\(ITEM\\)", "")
                .replaceAll("(?i)destino como tabela \\(tabela\\)", "destino como tabela")
                .replaceAll("(?i)componente do tipo tabela \\(tabela\\)", "componente de tabela")
                .replaceAll("(?i)componente tabela \\(tabela\\) como visão relacionada alvo", "tabela")
                .replaceAll("(?i)componente tabela \\(tabela\\)", "tabela")
                .replaceAll("(?i)componente de tabela como visão relacionada alvo", "tabela")
                .replaceAll("(?i)como visão relacionada alvo", "como destino")
                .replaceAll("(?i)como tabela \\(tabela\\)", "como tabela")
                .replaceAll("(?i)\\btabelas\\\"\\s*/\\s*visões\\b", "tabelas e visões")
                .replaceAll("(?i)\\btabelas\\s*/\\s*visões\\b", "tabelas e visões")
                .replaceAll("(?i)\\bFonte disponível:\\s*fonte\\b", "Fonte disponível")
                .replaceAll("(?i)\\bfonte\\s*:\\s*fonte confirmada\\b",
                        "fonte governada confirmada pelo catálogo")
                .replaceAll("(?i)\\bcontrato e runtime\\b", "contrato publicado")
                .replaceAll("(?i)\\bruntime\\b", "execução")
                .replaceAll("(?i)tabelas e visões relacionadas por visão",
                        "Tabelas e visões relacionadas ao registro")
                .replaceAll("(?i)^visões relacionadas/tabelas relacionáveis",
                        "Visões relacionadas e tabelas disponíveis")
                .replaceAll("(?i)visões relacionadas/tabelas relacionáveis",
                        "visões relacionadas e tabelas disponíveis")
                .replaceAll("(?i)\\babrir/abranger\\b", "abrir")
                .replaceAll("(?i)\\blayout \\(autor\\)", "layout da página")
                .replaceAll("(?m)^-\\s+([^-\\n]+?)\\. destino:", "- $1. Destino:")
                .replaceAll("(?i)\\. destino:", ". Destino:")
                .replaceAll("(?i)campos confirmados inferido", "campos confirmados")
                .replaceAll("(?i)campos confirmados disponível", "campos confirmados disponíveis")
                .replaceAll("(?i)campos completos do campos confirmados disponível",
                        "campos completos disponíveis")
                .replaceAll("(?i)\\boperacao\\b", "operação")
                .replaceAll("(?i)\\boperacoes\\b", "operações")
                .replaceAll("(?i)\\bnegocio\\b", "negócio")
                .replaceAll("(?i)\\bvoce\\b", "você")
                .replaceAll("(?i)\\bnao\\b", "não")
                .replaceAll("(?i)\\bdisponivel\\b", "disponível")
                .replaceAll("(?i)\\bdisponiveis\\b", "disponíveis")
                .replaceAll("(?i)\\bnivel\\b", "nível")
                .replaceAll("(?i)\\bprevia\\b", "prévia")
                .replaceAll("(?i)\\banalitico\\b", "analítico")
                .replaceAll("(?i)\\bmetricas\\b", "métricas")
                .replaceAll("\\s+\\)", ")")
                .trim();
        return restoreCanonicalConfigPaths(cleaned);
    }

    public static String titleCase(String value) {
        String text = display(value);
        if (text.isBlank()) {
            return "";
        }
        String[] words = text.split("\\s+");
        List<String> titled = new ArrayList<>();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            String lower = word.toLowerCase(PT_BR);
            titled.add(lower.substring(0, 1).toUpperCase(PT_BR) + lower.substring(1));
        }
        return String.join(" ", titled);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String repairMojibake(String value) {
        if (value == null || value.isBlank() || (!value.contains("Ã") && !value.contains("Â"))) {
            return value;
        }
        try {
            String repaired = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            return replacementCount(repaired) <= replacementCount(value) ? repaired : value;
        } catch (RuntimeException ex) {
            return value;
        }
    }

    private static long replacementCount(String value) {
        return value == null ? 0 : value.chars().filter(ch -> ch == '\uFFFD').count();
    }

    private static String protectCanonicalConfigPaths(String value) {
        return value
                .replace("/api/praxis/config/domain-rules/intake", "__PRAXIS_DOMAIN_RULES_INTAKE__")
                .replace("/api/praxis/config/domain-rules/simulations", "__PRAXIS_DOMAIN_RULES_SIMULATIONS__")
                .replace("/api/praxis/config/domain-rules/publications", "__PRAXIS_DOMAIN_RULES_PUBLICATIONS__");
    }

    private static String restoreCanonicalConfigPaths(String value) {
        return value
                .replace("__PRAXIS_DOMAIN_RULES_INTAKE__", "/api/praxis/config/domain-rules/intake")
                .replace("__PRAXIS_DOMAIN_RULES_SIMULATIONS__", "/api/praxis/config/domain-rules/simulations")
                .replace("__PRAXIS_DOMAIN_RULES_PUBLICATIONS__", "/api/praxis/config/domain-rules/publications");
    }
}
