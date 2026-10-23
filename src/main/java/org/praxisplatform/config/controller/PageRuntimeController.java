package org.praxisplatform.config.controller;

import java.security.Principal;
import java.util.UUID;
import org.praxisplatform.config.dto.PageCatalogResponse;
import org.praxisplatform.config.dto.PublishedPageResponse;
import org.praxisplatform.config.service.PageRuntimeReadService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Published-only reads; creation and all lifecycle writes keep their existing owners. */
@RestController
@ConditionalOnBean(PageRuntimeReadService.class)
@RequestMapping("/api/praxis/config/pages")
public class PageRuntimeController {
  private final PageRuntimeReadService pages;
  public PageRuntimeController(PageRuntimeReadService pages) { this.pages = pages; }

  @GetMapping
  public ResponseEntity<PageCatalogResponse> catalog(Principal principal,
      @RequestHeader("X-Praxis-Context-Version") String contextVersion,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String condition,
      @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
    var result = pages.catalog(principal, contextVersion, cursor, limit);
    return PublishedReadHttp.response(result, result.receiptEtag(), condition);
  }

  @GetMapping("/{pageId}/published")
  public ResponseEntity<PublishedPageResponse> published(Principal principal,
      @RequestHeader("X-Praxis-Context-Version") String contextVersion,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String condition,
      @PathVariable UUID pageId) {
    var result = pages.published(principal, contextVersion, pageId);
    return PublishedReadHttp.response(result, result.receiptEtag(), condition);
  }
}
