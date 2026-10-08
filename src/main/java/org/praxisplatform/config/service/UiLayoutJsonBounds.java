package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.config.service.UiLayoutLifecycleException.Code;

/** Internal tree/text preflight. No HTTP stream admission or global mapper changes. */
final class UiLayoutJsonBounds {
  static final int MAX_DOCUMENT_BYTES = UiLayoutDraftWorkspaceCodec.MAX_DOCUMENT_BYTES;
  static final int MAX_DEPTH = UiLayoutMetadataCaptureCodec.MAX_NESTING_DEPTH;
  static final int MAX_INPUT_TEXT_BYTES = 1024 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<Class<?>> TYPES = Set.of(ObjectNode.class, ArrayNode.class, TextNode.class,
      BooleanNode.class, NullNode.class, IntNode.class, LongNode.class, ShortNode.class,
      BigIntegerNode.class, DecimalNode.class, DoubleNode.class, FloatNode.class);

  private UiLayoutJsonBounds() {}

  static void requireInputText(String text, Code code, Runnable checkpoint) {
    utf8Length(text, MAX_INPUT_TEXT_BYTES, code, checkpoint);
  }

  static int requireDocument(JsonNode root, Code code, Runnable checkpoint) {
    return requireTree(root, MAX_DOCUMENT_BYTES, MAX_DEPTH, code, checkpoint);
  }

  static int requireTree(JsonNode root, int maxBytes, int maxDepth, Code code, Runnable checkpoint) {
    if (root == null || root.getClass() != ObjectNode.class) throw invalid(code);
    var stack = new ArrayDeque<Frame>();
    Set<JsonNode> parents = Collections.newSetFromMap(new IdentityHashMap<>());
    stack.push(new Frame(root, 1));
    long minimumBytes = 0;
    while (!stack.isEmpty()) {
      checkpoint.run();
      Frame frame = stack.peek();
      JsonNode node = frame.node;
      if (!frame.entered) {
        if (!TYPES.contains(node.getClass())) throw invalid(code);
        frame.entered = true;
        if (node.isContainerNode()) {
          if (frame.depth > maxDepth || !parents.add(node)) throw invalid(code);
          minimumBytes += 2;
          if (node.isObject()) frame.fields = node.properties().iterator();
          else frame.values = node.elements();
        } else {
          if (node.isTextual()) minimumBytes += 2L + utf8Length(node.textValue(), maxBytes, code, checkpoint);
          else if (node.isNumber()) {
            if (node.isFloatingPointNumber() && (node instanceof DoubleNode || node instanceof FloatNode)
                && !Double.isFinite(node.doubleValue())) throw invalid(code);
            if (node instanceof BigIntegerNode && node.bigIntegerValue().bitLength() > 851
                || node instanceof DecimalNode && node.decimalValue().unscaledValue().bitLength() > 851) throw invalid(code);
            String token = node.asText();
            if (token.length() > 256) throw invalid(code);
            minimumBytes += token.length();
          } else minimumBytes += node.isNull() ? 4 : node.booleanValue() ? 4 : 5;
          stack.pop();
        }
      } else if (frame.fields != null && frame.fields.hasNext()) {
        var field = frame.fields.next();
        minimumBytes += 3L + utf8Length(field.getKey(), maxBytes, code, checkpoint);
        stack.push(new Frame(field.getValue(), frame.depth + 1));
      } else if (frame.values != null && frame.values.hasNext()) {
        stack.push(new Frame(frame.values.next(), frame.depth + 1));
      } else {
        parents.remove(node); stack.pop();
      }
      if (minimumBytes > maxBytes) throw invalid(code);
    }
    return measure(root, maxBytes, code, checkpoint);
  }

  /** Only for subtrees/keys whose syntax has already passed requireDocument. */
  static int measure(JsonNode value, Code code, Runnable checkpoint) {
    return measure(value, MAX_DOCUMENT_BYTES, code, checkpoint);
  }

  private static int measure(JsonNode value, int maxBytes, Code code, Runnable checkpoint) {
    checkpoint.run();
    var output = new CountedOutput(maxBytes, code, checkpoint);
    try { JSON.writeValue(output, value); }
    catch (IOException failure) {
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof UiLayoutLifecycleException known) throw known;
      }
      throw invalid(code);
    }
    checkpoint.run();
    return output.count;
  }

  static int utf8Length(String text, int limit, Code code, Runnable checkpoint) {
    if (text == null || text.length() > limit) throw invalid(code);
    long count = 0;
    for (int index = 0; index < text.length(); index++) {
      if ((index & 1023) == 0) checkpoint.run();
      char value = text.charAt(index);
      if (Character.isHighSurrogate(value)) {
        if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) throw invalid(code);
        count += 4;
      } else if (Character.isLowSurrogate(value)) throw invalid(code);
      else count += value < 128 ? 1 : value < 2048 ? 2 : 3;
      if (count > limit) throw invalid(code);
    }
    checkpoint.run();
    return (int) count;
  }

  private static UiLayoutLifecycleException invalid(Code code) {
    return new UiLayoutLifecycleException(code, "Lifecycle JSON syntax or budget is invalid.");
  }

  private static final class Frame {
    final JsonNode node;
    final int depth;
    boolean entered;
    Iterator<Map.Entry<String, JsonNode>> fields;
    Iterator<JsonNode> values;
    Frame(JsonNode node, int depth) { this.node = node; this.depth = depth; }
  }

  private static final class CountedOutput extends OutputStream {
    private final Code code;
    private final Runnable checkpoint;
    private final int maxBytes;
    private int count;
    CountedOutput(int maxBytes, Code code, Runnable checkpoint) { this.maxBytes = maxBytes; this.code = code; this.checkpoint = checkpoint; }
    @Override public void write(int value) { add(1); }
    @Override public void write(byte[] value, int offset, int length) { add(length); }
    private void add(int length) {
      checkpoint.run();
      if (length > maxBytes - count) throw invalid(code);
      count += length;
    }
  }
}
