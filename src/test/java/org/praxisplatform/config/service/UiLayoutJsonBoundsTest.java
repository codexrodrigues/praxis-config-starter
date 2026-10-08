package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.service.UiLayoutLifecycleException.Code;

@Tag("unit")
class UiLayoutJsonBoundsTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final Runnable active = () -> {};

  private void invalid(Runnable action, Code code) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(UiLayoutLifecycleException.class,
        failure -> assertThat(failure.getCode()).isEqualTo(code));
  }

  @Test void acceptsExactDocumentByteLimitAndRejectsOneMore() {
    var doc = mapper.createObjectNode().put("v", "x".repeat(UiLayoutJsonBounds.MAX_DOCUMENT_BYTES - 8));
    assertThat(UiLayoutJsonBounds.requireDocument(doc, Code.INVALID_REQUEST, active)).isEqualTo(262144);
    doc.put("v", "x".repeat(UiLayoutJsonBounds.MAX_DOCUMENT_BYTES - 7));
    invalid(() -> UiLayoutJsonBounds.requireDocument(doc, Code.INVALID_REQUEST, active), Code.INVALID_REQUEST);
  }

  @Test void countsMultibyteUtf8AndEscapedControlCharacters() {
    var doc = mapper.createObjectNode().put("v", "é".repeat((262144 - 8) / 2));
    assertThat(UiLayoutJsonBounds.requireDocument(doc, Code.INVALID_REQUEST, active)).isEqualTo(262144);
    doc.put("v", "é".repeat((262144 - 8) / 2) + "é");
    invalid(() -> UiLayoutJsonBounds.requireDocument(doc, Code.INVALID_REQUEST, active), Code.INVALID_REQUEST);
    doc.put("v", "\u0000".repeat(44000));
    invalid(() -> UiLayoutJsonBounds.requireDocument(doc, Code.INVALID_REQUEST, active), Code.INVALID_REQUEST);
  }

  @Test void countsContainerDepthAndRejects65BeforeRecursiveCopy() {
    var root = mapper.createObjectNode(); var child = root;
    for (int index = 1; index < 64; index++) child = child.putObject("child");
    child.put("scalar", true);
    UiLayoutJsonBounds.requireDocument(root, Code.INVALID_REQUEST, active);
    child.putArray("nested");
    invalid(() -> UiLayoutJsonBounds.requireDocument(root, Code.INVALID_REQUEST, active), Code.INVALID_REQUEST);
  }

  @Test void rejectsCyclesButAllowsSharedNonCyclicJsonSubtrees() {
    var root = mapper.createObjectNode(); root.set("self", root);
    invalid(() -> UiLayoutJsonBounds.requireDocument(root, Code.INVALID_STATE, active), Code.INVALID_STATE);
    root.remove("self"); var shared = mapper.createObjectNode().put("value", 1);
    root.set("a", shared); root.set("b", shared);
    UiLayoutJsonBounds.requireDocument(root, Code.INVALID_REQUEST, active);
  }

  @Test void rejectsForeignNodesBeforeExecutingTheirGetters() {
    var reads = new AtomicInteger();
    var root = mapper.createObjectNode().putPOJO("payload", new Object() {
      public String getSecret() { reads.incrementAndGet(); return "private"; }
    });
    invalid(() -> UiLayoutJsonBounds.requireDocument(root, Code.INVALID_REQUEST, active), Code.INVALID_REQUEST);
    assertThat(reads).hasValue(0);
    ObjectNode foreign = new ObjectNode(mapper.getNodeFactory()) {};
    invalid(() -> UiLayoutJsonBounds.requireDocument(foreign, Code.INVALID_REQUEST, active), Code.INVALID_REQUEST);
    assertThat(reads).hasValue(0);
    root.removeAll(); root.put("binary", new byte[] {1});
    invalid(() -> UiLayoutJsonBounds.requireDocument(root, Code.INVALID_REQUEST, active), Code.INVALID_REQUEST);
  }

  @Test void rejectsMalformedUnicodeInValuesAndPropertyNames() {
    for (String bad : new String[] {"\uD800", "\uDC00"}) {
      var root = mapper.createObjectNode().put("v", bad);
      invalid(() -> UiLayoutJsonBounds.requireDocument(root, Code.INVALID_REQUEST, active), Code.INVALID_REQUEST);
      root.removeAll(); root.put(bad, "value");
      invalid(() -> UiLayoutJsonBounds.requireDocument(root, Code.INVALID_REQUEST, active), Code.INVALID_REQUEST);
    }
    UiLayoutJsonBounds.requireDocument(mapper.createObjectNode().put("🧩", "ação"), Code.INVALID_REQUEST, active);
  }

  @Test void rejectsNonfiniteOrOversizedNumbersAndAccepts256DigitToken() {
    var root = mapper.createObjectNode().put("value", new BigInteger("1".repeat(256)));
    UiLayoutJsonBounds.requireDocument(root, Code.INVALID_REQUEST, active);
    root.put("value", new BigInteger("1".repeat(257)));
    invalid(() -> UiLayoutJsonBounds.requireDocument(root, Code.INVALID_REQUEST, active), Code.INVALID_REQUEST);
    for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      root.put("value", value);
      invalid(() -> UiLayoutJsonBounds.requireDocument(root, Code.INVALID_REQUEST, active), Code.INVALID_REQUEST);
    }
  }

  @Test void enforcesDirectStringBudgetInUtf8BeforeParsing() {
    UiLayoutJsonBounds.requireInputText("x".repeat(1048576), Code.INVALID_REQUEST, active);
    invalid(() -> UiLayoutJsonBounds.requireInputText("x".repeat(1048577), Code.INVALID_REQUEST, active), Code.INVALID_REQUEST);
    invalid(() -> UiLayoutJsonBounds.requireInputText("é".repeat(524289), Code.INVALID_REQUEST, active), Code.INVALID_REQUEST);
    invalid(() -> UiLayoutJsonBounds.requireInputText("\uD800", Code.INVALID_REQUEST, active), Code.INVALID_REQUEST);
  }

  @Test void preservesBudgetExpiryCodeDuringBoundedSerialization() {
    var checks = new AtomicInteger();
    invalid(() -> UiLayoutJsonBounds.measure(mapper.createObjectNode().put("value", "abc"), Code.INVALID_REQUEST,
        () -> { if (checks.incrementAndGet() >= 2) throw new UiLayoutLifecycleException(Code.SOURCE_UNAVAILABLE, "expired"); }),
        Code.SOURCE_UNAVAILABLE);
  }

  @Test void rejectsMissingOrNonObjectDocumentRoots() {
    invalid(() -> UiLayoutJsonBounds.requireDocument(null, Code.INVALID_STATE, active), Code.INVALID_STATE);
    invalid(() -> UiLayoutJsonBounds.requireDocument(mapper.createArrayNode(), Code.INVALID_REQUEST, active), Code.INVALID_REQUEST);
  }
}
