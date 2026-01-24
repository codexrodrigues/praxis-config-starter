package org.praxisplatform.config.rag;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass({QuestionAnswerAdvisor.class, RetrievalAugmentationAdvisor.class})
@ConditionalOnProperty(prefix = "praxis.ai.rag.chat", name = "enabled", havingValue = "true")
@ConditionalOnBean(VectorStore.class)
@EnableConfigurationProperties(RagChatAdvisorProperties.class)
public class RagChatAdvisorConfiguration {

    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(
            VectorStore vectorStore,
            RagChatAdvisorProperties properties) {
        SearchRequest request = SearchRequest.builder()
                .topK(properties.getTopK())
                .similarityThreshold(properties.getSimilarityThreshold())
                .build();
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(request)
                .build();
    }

    @Bean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            VectorStore vectorStore,
            RagChatAdvisorProperties properties) {
        VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(properties.getTopK())
                .similarityThreshold(properties.getSimilarityThreshold())
                .filterExpression(RagFilterContext::get)
                .build();
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .build();
    }
}
