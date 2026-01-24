package org.praxisplatform.config.rag;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RagChatAdvisorService {

    private final ObjectProvider<QuestionAnswerAdvisor> questionAnswerAdvisorProvider;
    private final ObjectProvider<RetrievalAugmentationAdvisor> retrievalAdvisorProvider;
    private final RagChatAdvisorProperties properties;

    public List<Advisor> resolveAdvisors() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        String mode = properties.getMode() != null ? properties.getMode().trim().toLowerCase() : "naive";
        if ("modular".equals(mode)) {
            RetrievalAugmentationAdvisor advisor = retrievalAdvisorProvider.getIfAvailable();
            return advisor != null ? List.of(advisor) : List.of();
        }
        QuestionAnswerAdvisor advisor = questionAnswerAdvisorProvider.getIfAvailable();
        return advisor != null ? List.of(advisor) : List.of();
    }
}
