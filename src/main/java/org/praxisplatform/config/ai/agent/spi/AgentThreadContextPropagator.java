package org.praxisplatform.config.ai.agent.spi;

/**
 * SPI neutra para captura imutavel e restauracao segura de contextos de thread (como Spring Security
 * ou caller context de autorizacao) entre a thread HTTP e a thread worker do stream.
 */
public interface AgentThreadContextPropagator {

    Snapshot capture();

    @FunctionalInterface
    interface Snapshot {
        AutoCloseable attach();
    }
}
