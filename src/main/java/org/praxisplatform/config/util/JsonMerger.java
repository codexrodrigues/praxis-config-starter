package org.praxisplatform.config.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Iterator;

@Component
public class JsonMerger {

    /**
     * Merges two JsonNodes. The updateNode overrides the mainNode.
     * This is a deep merge for Objects, but Arrays are replaced (unless customized).
     *
     * @param mainNode   The base configuration (SYSTEM)
     * @param updateNode The override configuration (USER)
     * @return The merged JsonNode
     */
    public JsonNode merge(JsonNode mainNode, JsonNode updateNode) {
        if (mainNode == null) return updateNode;
        if (updateNode == null) return mainNode;

        Iterator<String> fieldNames = updateNode.fieldNames();

        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            JsonNode jsonNode = mainNode.get(fieldName);

            // If the field exists in the mainNode and both are Objects, recurse
            if (jsonNode != null && jsonNode.isObject() && updateNode.get(fieldName).isObject()) {
                merge(jsonNode, updateNode.get(fieldName));
            } else {
                // Otherwise, if mainNode is an ObjectNode, just overwrite the field
                if (mainNode instanceof ObjectNode) {
                    JsonNode value = updateNode.get(fieldName);
                    ((ObjectNode) mainNode).set(fieldName, value);
                }
            }
        }
        return mainNode;
    }
}
