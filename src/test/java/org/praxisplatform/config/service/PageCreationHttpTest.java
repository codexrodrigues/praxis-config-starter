package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.controller.*;
import org.praxisplatform.config.dto.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("unit")
class PageCreationHttpTest {
  private final PageCreationService pages =
      mock(PageCreationService.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
  private final UiLayoutLifecycleReadService reads = mock(UiLayoutLifecycleReadService.class);
  private final UiLayoutLifecycleCommandService commands =
      mock(UiLayoutLifecycleCommandService.class);
  private final PageCreationCodec codec = new PageCreationCodec();
  private final PageCreationCommand command =
      new PageCreationCommand(
          UUID.randomUUID(), new PageSourceReference.Native("fixture:page", "r1"), "Copy");
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    // The host deliberately ignores unknown Jackson properties. The raw P0 decoder must still
    // reject them.
    var mapper =
        new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    when(pages.decode(anyString())).thenAnswer(call -> codec.command(call.getArgument(0)));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new PageCreationController(pages),
                new UiLayoutLifecycleController(
                    reads, commands, new UiLayoutLifecycleRequestDecoder(mapper)))
            .setControllerAdvice(
                new UiLayoutResolutionExceptionHandler(), new UiLayoutRevisionRequestBodyAdvice())
            .setMessageConverters(
                new StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8),
                new MappingJackson2HttpMessageConverter(mapper))
            .build();
  }

  @Test
  void rawWireIsClosedBeforeHostJacksonCanDiscardInput() throws Exception {
    String valid = codec.write(command);
    for (String invalid :
        List.of(
            valid.replace("\"title\":", "\"unknown\":true,\"title\":"),
            valid.replace("\"title\":", "\"title\":\"duplicate\",\"title\":"),
            valid + " {}",
            valid.replace(command.creationKey().toString(), "not-a-uuid"),
            valid.replace("\"Copy\"", "null"),
            valid.replace("\"r1\"", "1"),
            valid.replace("\"sourceId\":", "\"extra\":null,\"sourceId\":"))) {
      mvc.perform(
              post("/api/praxis/config/pages")
                  .principal(() -> "actor")
                  .header("X-Praxis-Context-Version", "c1")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalid))
          .andExpect(status().isBadRequest());
    }
    verify(pages, never()).create(any(), anyString(), any());
  }

  @Test
  void locationIsUsableAndReplayNeverBecomesConditional304() throws Exception {
    UUID pageId = UUID.randomUUID(), draftId = UUID.randomUUID();
    var target = new UiLayoutTarget("praxis-dynamic-page", pageId.toString());
    String etag = UUID.randomUUID().toString();
    var receipt =
        new PageCreationReceipt(
            command.creationKey(),
            pageId,
            target,
            "/pages/" + pageId,
            draftId,
            "\"" + etag + "\"",
            command.source());
    when(pages.create(any(), eq("c1"), eq(command)))
        .thenReturn(new PageCreationService.CreationOutcome(receipt, true));
    when(reads.draft(any(), eq("c1"), eq(target), eq(draftId)))
        .thenReturn(
            new UiLayoutDraftWorkspaceReceipt(
                "1",
                new UiLayoutDraftReceipt(
                    draftId.toString(), target, "DRAFT", etag, Instant.EPOCH, Instant.EPOCH),
                List.of(),
                null,
                null));
    var response =
        mvc.perform(
                post("/api/praxis/config/pages")
                    .principal(() -> "actor")
                    .header("X-Praxis-Context-Version", "c1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(codec.write(command)))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("ETag", receipt.draftEtag()))
            .andReturn()
            .getResponse();
    String location = response.getHeader("Location");
    assertThat(location)
        .contains("rootComponentType=praxis-dynamic-page", "rootComponentId=" + pageId);
    mvc.perform(get(location).principal(() -> "actor").header("X-Praxis-Context-Version", "c1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.draft.draftRef").value(draftId.toString()));
    when(pages.create(any(), eq("c1"), eq(command)))
        .thenReturn(new PageCreationService.CreationOutcome(receipt, false));
    mvc.perform(
            post("/api/praxis/config/pages")
                .principal(() -> "actor")
                .header("X-Praxis-Context-Version", "c1")
                .header("If-None-Match", receipt.draftEtag())
                .contentType(MediaType.APPLICATION_JSON)
                .content(codec.write(command)))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    when(pages.byCreationKey(any(), eq("c1"), eq(command.creationKey()))).thenReturn(receipt);
    mvc.perform(
            get("/api/praxis/config/pages/by-creation-key")
                .param("creationKey", command.creationKey().toString())
                .principal(() -> "actor")
                .header("X-Praxis-Context-Version", "c1")
                .header("If-None-Match", receipt.draftEtag()))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  void conflictsAndAdmissionErrorsAreSanitizedAndNeverCached() throws Exception {
    var statuses =
        java.util.Map.of(
            UiLayoutLifecycleException.Code.INVALID_STATE,
            409,
            UiLayoutLifecycleException.Code.DENIED,
            403,
            UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
            503);
    for (var entry : statuses.entrySet()) {
      when(pages.create(any(), eq("c1"), eq(command)))
          .thenThrow(new UiLayoutLifecycleException(entry.getKey(), "private-ledger-secret"));
      mvc.perform(
              post("/api/praxis/config/pages")
                  .principal(() -> "actor")
                  .header("X-Praxis-Context-Version", "c1")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(codec.write(command)))
          .andExpect(status().is(entry.getValue()))
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(
              content()
                  .string(
                      org.hamcrest.Matchers.not(
                          org.hamcrest.Matchers.containsString("private-ledger-secret"))));
      reset(pages);
      when(pages.decode(anyString())).thenAnswer(call -> codec.command(call.getArgument(0)));
    }
  }

  @Test
  void oversizedAndMalformedUtf8BodiesAreRejectedBeforeDecode() throws Exception {
    mvc.perform(
            post("/api/praxis/config/pages")
                .principal(() -> "actor")
                .header("X-Praxis-Context-Version", "c1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(UiLayoutLifecycleRequestDecoder.MAX_REVISION_BODY_BYTES + 1)))
        .andExpect(status().isPayloadTooLarge());
    mvc.perform(
            post("/api/praxis/config/pages")
                .principal(() -> "actor")
                .header("X-Praxis-Context-Version", "c1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new byte[] {(byte) 0xc3, (byte) 0x28}))
        .andExpect(status().isBadRequest());
    verify(pages, never()).decode(anyString());
  }
}
