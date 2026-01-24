package org.praxisplatform.config.rag;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagVectorStoreService {

    private final ObjectProvider<VectorStore> vectorStoreProvider;

    public boolean isAvailable() {
        return vectorStoreProvider.getIfAvailable() != null;
    }

    public void upsertDocuments(List<Document> documents) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null || documents == null || documents.isEmpty()) {
            return;
        }
        List<Document> validDocuments = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Document document : documents) {
            if (document == null || document.getId() == null || document.getId().isBlank()) {
                continue;
            }
            ids.add(document.getId());
            validDocuments.add(document);
        }
        if (validDocuments.isEmpty()) {
            return;
        }
        vectorStore.delete(ids);
        vectorStore.add(validDocuments);
    }

    public List<Document> search(String query, int limit, Filter.Expression filterExpression) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null || query == null || query.isBlank()) {
            return List.of();
        }
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(1, limit))
                .similarityThresholdAll();
        if (filterExpression != null) {
            builder.filterExpression(filterExpression);
        }
        return vectorStore.similaritySearch(builder.build());
    }
}
