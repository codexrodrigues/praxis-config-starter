package org.praxisplatform.config.ai.authoring;

record AgenticAuthoringTurnEventAppendResult(String eventType, boolean appended) {

    public boolean appendedType(String expectedType) {
        return appended && expectedType != null && expectedType.equalsIgnoreCase(eventType);
    }
}
