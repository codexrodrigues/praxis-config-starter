package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiActionPlan {
    private List<Action> actions;
    private List<Ambiguity> ambiguities;
    private List<Integer> contextRequest;
    private String message;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Action {
        private String type;
        private String target;
        private JsonNode value;
        private JsonNode params;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Ambiguity {
        private String alias;
        private List<String> candidates;
        private String reason;
    }
}
