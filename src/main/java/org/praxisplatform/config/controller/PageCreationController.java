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
