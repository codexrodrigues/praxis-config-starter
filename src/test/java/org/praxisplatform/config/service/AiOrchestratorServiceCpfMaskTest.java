package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Constructor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.AiIntentClassification;
import org.springframework.test.util.ReflectionTestUtils;

class AiOrchestratorServiceCpfMaskTest {

  private AiOrchestratorService service;

  @BeforeEach
  void setUp() {
    service =
        new AiOrchestratorService(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new ObjectMapper(),
            null,
            mock(AiThreadService.class),
            mock(AiMessageService.class));
  }

  @Test
  void inferFormatFromPromptReturnsCpfMask() {
    String prompt = "Formate a coluna CPF (000.000.000-00 (padrão CPF))";
    String mask =
        (String)
            ReflectionTestUtils.invokeMethod(
                service, "inferFormatFromPrompt", prompt, List.of());

    assertThat(mask).isEqualTo("000.000.000-00");
  }

  @Test
  void normalizeFormatValueTreatsNullStringAsMissing() {
    String normalized =
        (String)
            ReflectionTestUtils.invokeMethod(
                service, "normalizeFormatValue", "null", List.of());

    assertThat(normalized).isNull();
  }

  @Test
  void buildMaskOptionsForCpfContainsCpfMask() {
    @SuppressWarnings("unchecked")
    List<?> options =
        (List<?>)
            ReflectionTestUtils.invokeMethod(
                service, "buildMaskOptionsForField", "cpf");

    assertThat(options).isNotEmpty();
    String firstValue =
        (String) ReflectionTestUtils.getField(options.get(0), "value");
    assertThat(firstValue).isEqualTo("000.000.000-00");
  }

  @Test
  void applySelectedFormatOverrideFillsMissingFormat() throws Exception {
    Constructor<?> ctor =
        Class.forName(
                "org.praxisplatform.config.service.AiOrchestratorService$ColumnDescriptor")
            .getDeclaredConstructor(String.class, String.class);
    ctor.setAccessible(true);
    Object column = ctor.newInstance("cpf", "CPF");

    List<?> updated =
        (List<?>)
            ReflectionTestUtils.invokeMethod(
                service,
                "applySelectedFormatOverride",
                List.of(
                    org.praxisplatform.config.dto.AiActionItem.builder()
                        .type("SET_FORMAT")
                        .field("cpf")
                        .value(null)
                        .build()),
                ReflectionTestUtils.invokeMethod(
                    service,
                    "extractSelectedFormatFromHints",
                    new ObjectMapper()
                        .readTree(
                            """
                            {
                              "optionSelected": {
                                "targetField": "cpf",
                                "selection": { "value": "000.000.000-00", "mode": "mask" }
                              }
                            }
                            """)),
                null,
                List.of(column),
                List.of("field", "header"),
                null);

    Object action = updated.get(0);
    String value = (String) ReflectionTestUtils.getField(action, "value");
    assertThat(value).isEqualTo("000.000.000-00");
  }

  @Test
  void normalizePatchDoesNotSetTypeForMaskFormat() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String raw =
        """
        {
          "columns": [
            { "field": "cpf", "format": "000.000.000-00" }
          ]
        }
        """;
    Object normalized =
        ReflectionTestUtils.invokeMethod(service, "normalizePatch", "praxis-table", mapper.readTree(raw));
    String serialized = mapper.writeValueAsString(normalized);
    assertThat(serialized).contains("\"format\":\"000.000.000-00\"");
    assertThat(serialized).doesNotContain("\"type\":\"date\"");
  }

  @Test
  void buildFormatClarificationPayloadUsesStructuredOptionsForCpf() {
    AiIntentClassification intent =
        AiIntentClassification.builder()
            .intent("update_column_rules")
            .category("format")
            .targetField("cpf")
            .needsClarification(true)
            .options(List.of("000.000.000-00 (formato CPF padrão)"))
            .build();

    Object payload =
        ReflectionTestUtils.invokeMethod(
            service, "buildFormatClarificationPayload", intent, List.of());

    @SuppressWarnings("unchecked")
    List<String> options = (List<String>) ReflectionTestUtils.getField(payload, "options");
    @SuppressWarnings("unchecked")
    List<?> payloads = (List<?>) ReflectionTestUtils.getField(payload, "payloads");

    assertThat(options).anyMatch(option -> option.contains("000.000.000-00"));
    assertThat(payloads).isNotEmpty();
  }
}
