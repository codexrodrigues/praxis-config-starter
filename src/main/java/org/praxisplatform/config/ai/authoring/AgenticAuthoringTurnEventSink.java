package org.praxisplatform.config.ai.authoring;

interface AgenticAuthoringTurnEventSink {

    AgenticAuthoringTurnEventAppendResult append(String type, Object payload);

    boolean terminalReached();
}
