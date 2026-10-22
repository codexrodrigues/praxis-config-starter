package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PageCopyTransformationTest {
  private final CanonicalJsonHashService hashes = new CanonicalJsonHashService(UiLayoutPageRevisionReproductionTest.JSON);
  private final PageCopyTransformation copy = new PageCopyTransformation(hashes);
  private static final String SOURCE = "11111111-1111-4111-8111-111111111111";
  private static final String TARGET = "44444444-4444-4444-8444-444444444444";
  private static final String FIRST = "22222222-2222-4222-8222-222222222222";
  private static final String SECOND = "33333333-3333-4333-8333-333333333333";
  private static final String NEXT_FIRST = "55555555-5555-4555-8555-555555555555";
  private static final String NEXT_SECOND = "66666666-6666-4666-8666-666666666666";
  private static final String NEXT_LINK = "77777777-7777-4777-8777-777777777777";

  @Test void typedNamespacesRemapOnlyIdentitiesAndPreserveSourceAndAuthoredPresence() throws Exception {
    var source = UiLayoutPageRevisionReproductionTest.page();
    ((ObjectNode) source.at("/config/definition/widgets/0/definition/inputs/config")).put("title", FIRST);
    UiLayoutPageRevisionReproductionTest.input(source,0).putNull("emptyState");
    ((ObjectNode) source.at("/config/definition")).set("composition", UiLayoutPageRevisionReproductionTest.JSON.readTree("""
      {"version":"1.0.0","links":[{"id":"22222222-2222-4222-8222-222222222222","intent":"event-propagation",
      "from":{"kind":"component-port","ref":{"widget":"22222222-2222-4222-8222-222222222222","port":"submitted","direction":"output"}},
      "to":{"kind":"component-port","ref":{"widget":"33333333-3333-4333-8333-333333333333","port":"refresh","direction":"input"}}}]}
      """));
    var before = source.deepCopy();
    var result = copy.reproduce(source,SOURCE,TARGET,"Copied",List.of(
        new PageCopyTransformation.Identity("widget",FIRST,NEXT_FIRST),
        new PageCopyTransformation.Identity("widget",SECOND,NEXT_SECOND),
        new PageCopyTransformation.Identity("link",FIRST,NEXT_LINK)));
    assertEquals(before,source);
    assertEquals(NEXT_FIRST,result.document().at("/config/definition/widgets/0/key").asText());
    assertEquals(NEXT_LINK,result.document().at("/config/definition/composition/links/0/id").asText());
    assertEquals(NEXT_FIRST,result.document().at("/config/definition/composition/links/0/from/ref/widget").asText());
    assertEquals(FIRST,result.document().at("/config/definition/widgets/0/definition/inputs/config/title").asText());
    assertTrue(result.document().at("/config/definition/widgets/0/definition/inputs/emptyState").isNull());
    assertEquals("Copied",result.document().at("/config/presentation/title").asText());
    assertEquals("home",result.document().at("/config/presentation/icon").asText());
    assertTrue(result.document().at("/config/navigation").isEmpty());
    assertEquals(hashes.sha256Exact(source),result.sourceHash());
    assertEquals(hashes.sha256Exact(result.document()),result.outputHash());
  }

  @Test void rejectsMissingExtraRepeatedOrIntersectingMappingsAndInvalidTitle() throws Exception {
    var source = UiLayoutPageRevisionReproductionTest.page();
    var valid = List.of(new PageCopyTransformation.Identity("widget",FIRST,NEXT_FIRST),new PageCopyTransformation.Identity("widget",SECOND,NEXT_SECOND));
    for(var map : List.of(List.<PageCopyTransformation.Identity>of(),
        List.of(valid.getFirst(),valid.getFirst()),
        List.of(valid.getFirst(),new PageCopyTransformation.Identity("widget",SECOND,NEXT_FIRST)),
        List.of(valid.getFirst(),new PageCopyTransformation.Identity("widget",SECOND,SOURCE)),
        List.of(valid.getFirst(),new PageCopyTransformation.Identity("widget",SECOND,FIRST)),
        List.of(valid.getFirst(),new PageCopyTransformation.Identity("widget",SECOND,TARGET)),
        List.of(valid.getFirst(),valid.get(1),new PageCopyTransformation.Identity("link",FIRST,NEXT_LINK))))
      assertThrows(UiLayoutLifecycleException.class,()->copy.reproduce(source,SOURCE,TARGET,"Copy",map));
    assertThrows(UiLayoutLifecycleException.class,()->copy.reproduce(source,SOURCE,SOURCE,"Copy",valid));
    assertThrows(UiLayoutLifecycleException.class,()->copy.reproduce(source,SOURCE,FIRST,"Copy",valid));
    assertThrows(UiLayoutLifecycleException.class,()->copy.reproduce(source,SOURCE,TARGET," ",valid));
  }

  @Test void allocatedCopyReproducesWithoutNewAllocationAndDefensivelyCopiesContent() throws Exception {
    var source=UiLayoutPageRevisionReproductionTest.page();
    var allocated=copy.allocate(source,UUID.fromString(SOURCE),UUID.fromString(TARGET),"Copy");
    var reproduced=copy.reproduce(source,SOURCE,TARGET,"Copy",allocated.identityMap());
    assertEquals(allocated,reproduced);
    ((ObjectNode)allocated.document()).removeAll();
    assertFalse(allocated.document().isEmpty());
  }
}
