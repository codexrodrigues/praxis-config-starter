package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.UUID;
import org.praxisplatform.config.dto.PageCreationReceipt;
import org.praxisplatform.config.service.PageCreationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

/**
 * Creation/reconciliation only. Published page resolution and discovery are separate owner
 * contracts.
 */
@RestController
@ConditionalOnBean(PageCreationService.class)
@RequestMapping("/api/praxis/config/pages")
public class PageCreationController {
  private final PageCreationService pages;

  public PageCreationController(PageCreationService pages) {
    this.pages = pages;
  }

  @io.swagger.v3.oas.annotations.Operation(
      summary = "Create an independent governed page from an exact authorized source",
      description =
          "Atomically registers server-generated identity, route, immutable provenance, draft and"
              + " metadata captures. Equal creation-key replay returns the original identity with"
              + " the current draft ETag; a different command conflicts. Publication uses existing"
              + " ui-layout lifecycle commands.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      required = true,
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = org.praxisplatform.config.dto.PageCreationCommand.class)))
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description =
            "Created atomically; no-store, strong draft ETag and usable workspace Location."),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description =
            "Same creation recovered under current authority; no recapture or conditional 304."),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description =
            "Creation key already belongs to a different command or current context changed.")
  })
  @PostMapping
  public PageCreationReceipt create(
      @RequestHeader("X-Praxis-Context-Version") String contextVersion,
      @RequestBody String raw,
      Principal principal,
      HttpServletResponse response) {
    var outcome = pages.create(principal, contextVersion, pages.decode(raw));
    response.setStatus(outcome.created() ? 201 : 200);
    headers(response, outcome.receipt());
    return outcome.receipt();
  }

  @io.swagger.v3.oas.annotations.Operation(
      summary = "Reconcile a page creation after a lost response",
      description =
          "Requires current READ_CREATION and READ_DRAFT in the original"
              + " actor/application/tenant/environment scope. Returns the creation identity and"
              + " selected-source reference with the current draft ETag; exposes no ancestral"
              + " ledger and does not require permission to the old source. Absence is not proof"
              + " that an in-flight creation failed. Always no-store, never 304.")
  @GetMapping("/by-creation-key")
  public PageCreationReceipt byCreationKey(
      @RequestHeader("X-Praxis-Context-Version") String contextVersion,
      @RequestParam UUID creationKey,
      Principal principal,
      HttpServletResponse response) {
    var result = pages.byCreationKey(principal, contextVersion, creationKey);
    headers(response, result);
    return result;
  }

  private void headers(HttpServletResponse response, PageCreationReceipt value) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setHeader(HttpHeaders.ETAG, value.draftEtag());
    response.setHeader(
        HttpHeaders.LOCATION,
        org.springframework.web.util.UriComponentsBuilder.fromPath(
                "/api/praxis/config/ui-layouts/drafts/{draftId}")
            .queryParam("rootComponentType", value.target().componentType())
            .queryParam("rootComponentId", value.target().componentId())
            .buildAndExpand(value.draftId())
            .encode()
            .toUriString());
    // Plain body deliberately avoids ResponseEntity's automatic If-None-Match/304 handling.
  }
}
