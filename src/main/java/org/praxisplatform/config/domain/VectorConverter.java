package org.praxisplatform.config.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converte listas de {@link Float} para a representacao textual esperada pelas colunas vetoriais
 * persistidas via JPA.
 */
@Converter(autoApply = false)
public class VectorConverter implements AttributeConverter<List<Float>, String> {

    @Override
    public String convertToDatabaseColumn(List<Float> attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }

    @Override
    public List<Float> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return null;
        }
        String content = dbData.replace("[", "").replace("]", "");
        if (content.trim().isEmpty()) {
            return List.of();
        }

        return Arrays.stream(content.split(","))
                .map(String::trim)
                .map(Float::parseFloat)
                .collect(Collectors.toList());
    }
}
