package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;

@Tag("unit")
class UiLayoutPageRevisionReproductionTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final UiLayoutTarget PAGE = new UiLayoutTarget("praxis-dynamic-page", "11111111-1111-4111-8111-111111111111");
  static final UiLayoutAuthoringDocumentDescriptor DESCRIPTOR = new UiLayoutAuthoringDocumentDescriptor("praxis.dynamic-page.editor", "fixture:page", "1");
  static final UiLayoutPatchDocumentDescriptor PATCH = new UiLayoutPatchDocumentDescriptor("fixture:patch", "praxis.ui-layout/v1");
  static ObjectNode page() throws Exception {
    return (ObjectNode) JSON.readTree("""
      {"kind":"praxis.dynamic-page.editor","version":1,"config":{"definition":{"widgets":[
        {"key":"22222222-2222-4222-8222-222222222222","definition":{"id":"praxis-dynamic-form","inputs":{"config":{"title":"Original","fields":[]}}}},
        {"key":"33333333-3333-4333-8333-333333333333","definition":{"id":"praxis-table","inputs":{"config":{"columns":[],"columnProjection":{"source":"schema"}}}}}
      ]},"presentation":{"title":"Page","icon":"home"},"navigation":[]}}
      """);
  }
  static ObjectNode input(ObjectNode page, int index) { return (ObjectNode) page.at("/config/definition/widgets/" + index + "/definition/inputs"); }
  private void reproduce(JsonNode before, JsonNode after) {
    var projection = UiLayoutAuthoringProjection.project(PAGE, DESCRIPTOR, before, after, PATCH, () -> {});
    UiLayoutPageRevisionReproduction.require(PAGE, DESCRIPTOR, before, after, PATCH, projection.compilePatch());
  }
  @Test void runtimeSchemaIsExactlyTheNormativeP0Schema() throws Exception {
    try (var resource = getClass().getResourceAsStream("/org/praxisplatform/config/page-publication.schema.json")) {
      assertEquals(JSON.readTree(Path.of("docs/ai/contracts/page-publication-p0/contracts.schema.json").toFile()), JSON.readTree(resource));
    }
  }
  @Test void allPageSchemaCasesRunThroughNativeCodecWithoutNormalization() throws Exception {
    var corpus = JSON.readTree(Path.of("docs/ai/contracts/page-publication-p0/corpus.json").toFile());
    for (var entry : corpus.path("schemaCases")) {
      if (!entry.path("contract").asText().equals("pageDocument")) continue;
      var value = entry.get("input"); var before = value.deepCopy();
      if (entry.path("valid").asBoolean()) reproduce(value, value);
      else assertThrows(UiLayoutLifecycleException.class, () -> reproduce(value, value), entry.path("id").asText());
      assertEquals(before, value);
    }
  }
  @Test void fullConfigAndChildArraysRoundTripWhileNullOwnerRulesStillApply() throws Exception {
    var before = page(); var after = before.deepCopy();
    ((ObjectNode) input(after, 0).get("config")).remove("title");
    ((ObjectNode) input(after, 0).get("config")).putArray("fields").addObject().putNull("layout");
    reproduce(before, after);
    ((ObjectNode) input(after, 0).get("config")).putNull("layout");
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(before, after));
    var tableNull = before.deepCopy();
    ((ObjectNode) input(tableNull, 1).get("config")).putArray("actions").addNull();
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(before, tableNull));
    var columns = before.deepCopy();
    ((ObjectNode) input(columns, 1).get("config")).putArray("columns").addObject().put("field", "id");
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(before, columns));
  }
  @Test void reconstructsPresenceNullAndAllFiveOwnerModes() throws Exception {
    for (String mode : List.of("create", "edit", "view", "presentation", "dossier")) {
      var value = page(); input(value, 0).put("mode", mode).putNull("emptyState").putNull("presentationModeGlobal")
          .putNull("presentation").put("autoOpenSettingsOnOutdated", false).put("snoozeMs", 0);
      var child = UiLayoutPageRevisionReproduction.children(value).values().iterator().next();
      assertEquals(mode, child.at("/bindings/mode").asText());
      assertTrue(child.at("/bindings/emptyState").isNull());
      assertTrue(child.at("/contextSnapshot/presentation").isNull());
      assertEquals(0, child.at("/contextSnapshot/schemaPrefs/snoozeMs").asInt());
      assertFalse(child.at("/contextSnapshot/schemaPrefs").has("notifyIfOutdated"));
      reproduce(value, value);
    }
    var absent = UiLayoutPageRevisionReproduction.children(page()).values().iterator().next();
    assertFalse(absent.has("bindings")); assertFalse(absent.has("contextSnapshot"));
    var invalid = page(); input(invalid, 0).put("mode", "unknown");
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(invalid, invalid));
  }
  @Test void fixedIdentityCannotChangeEnvelopeResourceOrComponent() throws Exception {
    var before = page();
    for (var field : List.of("mode", "emptyState", "backConfig", "presentationModeGlobal")) {
      var changed = before.deepCopy();
      switch(field) {
        case "mode" -> input(changed, 0).put(field, "edit");
        case "backConfig" -> input(changed, 0).putObject(field);
        default -> input(changed, 0).putNull(field);
      }
      assertThrows(UiLayoutLifecycleException.class, () -> reproduce(before, changed));
    }
    var resource = before.deepCopy(); input(resource, 1).put("resourcePath", "other");
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(before, resource));
    var unsupported = before.deepCopy(); input(unsupported, 0).put("formId", "user-id");
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(before, unsupported));
  }
  @Test void duplicateKeysAndOrphanLinksFailDespiteAtomicWidgetArrays() throws Exception {
    var duplicate = page(); ((ObjectNode) duplicate.at("/config/definition/widgets/1")).put("key", duplicate.at("/config/definition/widgets/0/key").asText());
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(duplicate, duplicate));
    var orphan = page(); ((ObjectNode) orphan.at("/config/definition")).set("composition", JSON.readTree("""
      {"links":[{"id":"44444444-4444-4444-8444-444444444444","intent":"event-propagation",
        "from":{"kind":"component-port","ref":{"widget":"55555555-5555-4555-8555-555555555555","port":"saved","direction":"output"}},
        "to":{"kind":"component-port","ref":{"widget":"33333333-3333-4333-8333-333333333333","port":"refresh","direction":"input"}}}]}
      """));
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(orphan, orphan));
  }

  @Test void admitsAllSixCorporateDeclarativeWidgetsReproducesEditsAndProtectsResourceBindings() throws Exception {
    ObjectNode dashboard = (ObjectNode) JSON.readTree("""
      {"kind":"praxis.dynamic-page.editor","version":1,"config":{"definition":{"widgets":[
        {"key":"10000000-0000-4000-8000-000000000001","definition":{"id":"praxis-chart","inputs":{
          "chartDocument":{"version":1,"series":[{"name":"Servidores"}]},
          "resourceKey":"administracao-pessoal.tabelas-vencimento",
          "projectionKey":"por-regime",
          "queryContext":null}}},
        {"key":"10000000-0000-4000-8000-000000000002","definition":{"id":"praxis-list","inputs":{
          "config":{"layout":"card","titleField":"nome"},
          "resourcePath":"/api/workforce/processos-progressao"}}},
        {"key":"10000000-0000-4000-8000-000000000003","definition":{"id":"praxis-rich-content","inputs":{
          "document":{"version":1,"blocks":[{"type":"heading","text":"Resumo Executivo"}]},
          "presetId":"executive-banner"}}},
        {"key":"10000000-0000-4000-8000-000000000004","definition":{"id":"praxis-tabs","inputs":{
          "tabs":[{"label":"Visão Geral"},{"label":"Auditoria"}],
          "selectedIndex":0,
          "mode":"group"}}},
        {"key":"10000000-0000-4000-8000-000000000005","definition":{"id":"praxis-stepper","inputs":{
          "steps":[{"label":"Apuração"},{"label":"Homologação"}],
          "linear":true,
          "orientation":"horizontal"}}},
        {"key":"10000000-0000-4000-8000-000000000006","definition":{"id":"praxis-expansion","inputs":{
          "panels":[{"title":"Fundamentação Legal"}],
          "multi":true}}},
        {"key":"10000000-0000-4000-8000-000000000007","definition":{"id":"praxis-organogram","inputs":{
          "config":{"cardPreset":"compact","height":420},
          "resourcePath":"/api/workforce/unidades-organizacionais",
          "queryContext":null,
          "topology":"orthogonal-compact",
          "hierarchyType":0,
          "includeExtinct":false,
          "rootScopeLabel":"Secretaria",
          "synthesizePolymorphicNodes":false}}},
        {"key":"10000000-0000-4000-8000-000000000008","definition":{"id":"praxis-progression-ladder","inputs":{
          "document":{"version":1,"careerName":"Magistério"},
          "variant":"staircase",
          "showVariantSwitcher":true,
          "showFinancialDetails":true,
          "selectedStepId":"A1"}}}
      ],"layout":{"orientation":"columns","columns":12,"gap":"16px"}},
      "presentation":{"title":"Painel Executivo de Carreiras","icon":"analytics"},"navigation":[]}}
      """);

    var children = UiLayoutPageRevisionReproduction.children(dashboard);
    assertEquals(8, children.size());
    assertEquals("praxis.chart.editor", children.get("10000000-0000-4000-8000-000000000001").path("kind").asText());
    assertEquals("praxis.list.editor", children.get("10000000-0000-4000-8000-000000000002").path("kind").asText());
    assertEquals("praxis.rich-content.editor", children.get("10000000-0000-4000-8000-000000000003").path("kind").asText());
    assertEquals("praxis.tabs.editor", children.get("10000000-0000-4000-8000-000000000004").path("kind").asText());
    assertEquals("praxis.stepper.editor", children.get("10000000-0000-4000-8000-000000000005").path("kind").asText());
    assertEquals("praxis.expansion.editor", children.get("10000000-0000-4000-8000-000000000006").path("kind").asText());
    assertEquals("praxis.organogram.editor", children.get("10000000-0000-4000-8000-000000000007").path("kind").asText());
    assertEquals("praxis.progression-ladder.editor", children.get("10000000-0000-4000-8000-000000000008").path("kind").asText());

    // Valid editorial mutations across chartDocument, list config, rich-content document, tabs, stepper, expansion, organogram, progression-ladder
    var edited = dashboard.deepCopy();
    ((ObjectNode) input(edited, 0).get("chartDocument")).put("title", "Distribuição por Regime");
    ((ObjectNode) input(edited, 1).get("config")).put("layout", "tile");
    ((ObjectNode) input(edited, 2).get("document")).put("subtitle", "Exercício 2026");
    input(edited, 3).put("selectedIndex", 1);
    input(edited, 4).put("orientation", "vertical");
    input(edited, 5).put("multi", false);
    input(edited, 6).put("topology", "pillars");
    input(edited, 6).put("includeExtinct", true);
    input(edited, 7).put("variant", "pillars");
    ((ObjectNode) input(edited, 7).get("document")).put("careerName", "Magistério Superior");
    reproduce(dashboard, edited);

    // Organogram admits resourcePath-only widget configurations without config/nodes
    var organogramResourceOnly = JSON.readTree("""
      {"kind":"praxis.dynamic-page.editor","version":1,"config":{
      "definition":{"widgets":[
        {"key":"20000000-0000-4000-8000-000000000001","definition":{"id":"praxis-organogram","inputs":{
          "resourcePath":"/api/workforce/unidades-organizacionais",
          "topology":"curved-hub"}}}
      ],"layout":{"orientation":"vertical","columns":12,"gap":"16px"}},
      "presentation":{"title":"Organograma Remoto","icon":"account_tree"},"navigation":[]}}
      """);
    reproduce(organogramResourceOnly, organogramResourceOnly);

    // Changing resourceKey, projectionKey, resourcePath or presetId on an existing widget key violates envelope immutability
    var retargetedChart = dashboard.deepCopy();
    input(retargetedChart, 0).put("resourceKey", "administracao-pessoal.outro-recurso");
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(dashboard, retargetedChart));

    var retargetedList = dashboard.deepCopy();
    input(retargetedList, 1).put("resourcePath", "/api/workforce/outro");
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(dashboard, retargetedList));

    var retargetedRich = dashboard.deepCopy();
    input(retargetedRich, 2).put("presetId", "other-preset");
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(dashboard, retargetedRich));

    var retargetedOrganogram = dashboard.deepCopy();
    input(retargetedOrganogram, 6).put("resourcePath", "/api/workforce/outras-unidades");
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(dashboard, retargetedOrganogram));

    // PageCopyTransformation allocates fresh UUIDs cleanly across all 8 declarative widgets
    var copied = new PageCopyTransformation(new CanonicalJsonHashService(JSON))
        .allocate(dashboard, java.util.UUID.fromString(PAGE.componentId()), java.util.UUID.randomUUID(), "Cópia do Painel");
    assertEquals(8, copied.identityMap().size());
    reproduce(copied.document(), copied.document());
  }

  @Test void admitsSafeCanvasSafeShellAndExtendedAnalyticsPresentation() throws Exception {
    ObjectNode pageWithCanvasAndShell = (ObjectNode) JSON.readTree("""
      {"kind":"praxis.dynamic-page.editor","version":1,"config":{
        "definition":{
          "widgets":[
            {"key":"22222222-2222-4222-8222-222222222222",
             "shell":{"kind":"dashboard-card","title":"Indicador Analítico","subtitle":"Por lotação","icon":"analytics","showHeader":true},
             "definition":{"id":"praxis-analytics-presentation","inputs":{
               "resourcePath":"/api/workforce/employees",
               "projectionId":"headcount-by-unit",
               "availableFamilies":["chart","analytic-table","kpi","summary-list"],
               "preferredFamily":"chart",
               "surfaceMode":"embedded",
               "controlsPlacement":"shell-header",
               "controlsDisplay":"icon-only",
               "coerceCrossFilters":true,
               "title":"Efetivo",
               "height":320}}},
            {"key":"33333333-3333-4333-8333-333333333333",
             "shell":{"kind":"none","showHeader":false},
             "definition":{"id":"praxis-table","inputs":{"config":{"columns":[],"columnProjection":{"source":"schema"}}}}}
          ],
          "canvas":{
            "mode":"grid",
            "columns":12,
            "rowUnit":"8px",
            "gap":"16px",
            "autoRows":"content",
            "collisionPolicy":"push-down",
            "items":{
              "22222222-2222-4222-8222-222222222222":{"widgetKey":"22222222-2222-4222-8222-222222222222","col":1,"row":1,"colSpan":6,"rowSpan":4,"zIndex":1},
              "33333333-3333-4333-8333-333333333333":{"col":7,"row":1,"colSpan":6,"rowSpan":4}
            }
          }
        },
        "presentation":{"title":"Painel com Grade","icon":"dashboard"},
        "navigation":[]
      }}
      """);

    var children = UiLayoutPageRevisionReproduction.children(pageWithCanvasAndShell);
    assertEquals(2, children.size());
    reproduce(pageWithCanvasAndShell, pageWithCanvasAndShell);

    var editedShellAndCanvas = pageWithCanvasAndShell.deepCopy();
    ((ObjectNode) editedShellAndCanvas.at("/config/definition/widgets/0/shell")).put("title", "Indicador Atualizado");
    ((ObjectNode) editedShellAndCanvas.at("/config/definition/canvas/items/22222222-2222-4222-8222-222222222222")).put("colSpan", 8);
    ((ObjectNode) editedShellAndCanvas.at("/config/definition/canvas/items/33333333-3333-4333-8333-333333333333")).put("col", 9).put("colSpan", 4);
    reproduce(pageWithCanvasAndShell, editedShellAndCanvas);

    // Rejects orphan canvas item
    var orphanCanvas = pageWithCanvasAndShell.deepCopy();
    ((ObjectNode) orphanCanvas.at("/config/definition/canvas/items"))
        .set("99999999-9999-4999-8999-999999999999", JSON.readTree("{\"col\":1,\"row\":5,\"colSpan\":6,\"rowSpan\":2}"));
    assertThrows(UiLayoutLifecycleException.class, () -> UiLayoutPageRevisionReproduction.children(orphanCanvas));

    // Rejects grid col + colSpan overflow
    var overflowCanvas = pageWithCanvasAndShell.deepCopy();
    ((ObjectNode) overflowCanvas.at("/config/definition/canvas/items/33333333-3333-4333-8333-333333333333")).put("col", 8).put("colSpan", 6);
    assertThrows(UiLayoutLifecycleException.class, () -> UiLayoutPageRevisionReproduction.children(overflowCanvas));

    // Rejects widgetKey mismatch inside canvas item
    var mismatchCanvas = pageWithCanvasAndShell.deepCopy();
    ((ObjectNode) mismatchCanvas.at("/config/definition/canvas/items/22222222-2222-4222-8222-222222222222")).put("widgetKey", "33333333-3333-4333-8333-333333333333");
    assertThrows(UiLayoutLifecycleException.class, () -> UiLayoutPageRevisionReproduction.children(mismatchCanvas));

    // Rejects arbitrary shell visual/action overrides
    var badShell = pageWithCanvasAndShell.deepCopy();
    ((ObjectNode) badShell.at("/config/definition/widgets/0/shell")).putObject("appearance");
    assertThrows(UiLayoutLifecycleException.class, () -> UiLayoutPageRevisionReproduction.children(badShell));
  }

  @Test
  void admitsKpiStripWithShellAndKpiCardAndPreservesAcrossReproduceAndPageCopy() throws Exception {
    ObjectNode kpiPage =
        (ObjectNode)
            JSON.readTree(
                """
                {
                  "kind": "praxis.dynamic-page.editor",
                  "version": 1,
                  "config": {
                    "definition": {
                      "widgets": [
                        {
                          "key": "11111111-1111-4111-8111-111111111111",
                          "shell": {
                            "kind": "dashboard-card",
                            "title": "Indicadores Executivos",
                            "icon": "analytics",
                            "showHeader": true
                          },
                          "definition": {
                            "id": "praxis-kpi-strip",
                            "inputs": {
                              "config": {
                                "schemaVersion": 1,
                                "density": "comfortable",
                                "columns": "auto",
                                "cards": [
                                  {
                                    "schemaVersion": 1,
                                    "id": "33333333-3333-4333-8333-333333333331",
                                    "presentation": {
                                      "label": "Taxa de Ativos",
                                      "format": "percent",
                                      "tone": "success"
                                    },
                                    "source": {
                                      "kind": "stats-query",
                                      "resourcePath": "workforce/assignments",
                                      "operation": "group-by",
                                      "request": {
                                        "field": "status",
                                        "metric": {"operation": "COUNT", "alias": "total"}
                                      }
                                    },
                                    "derivation": {
                                      "kind": "ratio",
                                      "numeratorKeys": ["ACTIVE"],
                                      "denominatorMode": "population-total"
                                    },
                                    "governance": {"lockLevel": "metric-locked"}
                                  }
                                ]
                              }
                            }
                          }
                        },
                        {
                          "key": "22222222-2222-4222-8222-222222222222",
                          "definition": {
                            "id": "praxis-kpi-card",
                            "inputs": {
                              "density": "compact",
                              "config": {
                                "schemaVersion": 1,
                                "id": "33333333-3333-4333-8333-333333333332",
                                "presentation": {
                                  "label": "Admissoes Recentes",
                                  "format": "integer"
                                },
                                "source": {
                                  "kind": "verified-recipe",
                                  "resourcePath": "workforce/assignments",
                                  "recipeId": "recent-hires",
                                  "parameters": {"limit": 12}
                                },
                                "derivation": {
                                  "kind": "scalar",
                                  "aggregation": "total"
                                },
                                "governance": {"lockLevel": "full"}
                              }
                            }
                          }
                        }
                      ]
                    },
                    "presentation": {"title": "Executive KPIs", "icon": "analytics"},
                    "navigation": []
                  }
                }
                """);

    var updatedKpiPage = kpiPage.deepCopy();
    ((ObjectNode) input(updatedKpiPage, 0).at("/config/cards/0/presentation"))
        .put("label", "Taxa de Ativos Ajustada")
        .put("tone", "info");
    reproduce(kpiPage, updatedKpiPage);
    UiLayoutKpiStructureValidator.validateStructureOnly("praxis.dynamic-page.editor", updatedKpiPage);

    // Rejects anti-SSRF resourcePath
    var badSsrf = kpiPage.deepCopy();
    ((ObjectNode) input(badSsrf, 1).at("/config/source"))
        .put("resourcePath", "https://evil.example/api");
    assertThrows(
        UiLayoutLifecycleException.class, () -> UiLayoutPageRevisionReproduction.children(badSsrf));
    assertThrows(
        UiLayoutLifecycleException.class,
        () -> UiLayoutKpiStructureValidator.validateStructureOnly("praxis.dynamic-page.editor", badSsrf));

    // Rejects unknown property under closed KPI schema
    var extraProp = kpiPage.deepCopy();
    ((ObjectNode) input(extraProp, 0).at("/config/cards/0/presentation"))
        .put("arbitraryExtraField", "nope");
    assertThrows(
        UiLayoutLifecycleException.class,
        () -> UiLayoutPageRevisionReproduction.children(extraProp));

    // PageCopyTransformation allocates fresh UUIDs and preserves KPI card specs and lockLevels
    var copied =
        new PageCopyTransformation(new CanonicalJsonHashService(JSON))
            .allocate(
                updatedKpiPage,
                java.util.UUID.fromString(PAGE.componentId()),
                java.util.UUID.randomUUID(),
                "Copia KPIs Executivos");
    assertEquals(2, copied.identityMap().size());
    reproduce(copied.document(), copied.document());
    JsonNode copiedWidgets = copied.document().at("/config/definition/widgets");
    assertEquals(
        "metric-locked",
        copiedWidgets
            .at("/0/definition/inputs/config/cards/0/governance/lockLevel")
            .asText());
    assertEquals(
        "full",
        copiedWidgets.at("/1/definition/inputs/config/governance/lockLevel").asText());
  }
}
