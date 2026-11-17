package org.praxisplatform.config.ai.agent.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agrega multiplos {@link AgentThreadContextPropagator} garantindo fechamento em ordem reversa.
 */
public class CompositeAgentThreadContextPropagator {

    private final List<AgentThreadContextPropagator> propagators;

    public CompositeAgentThreadContextPropagator(List<AgentThreadContextPropagator> propagators) {
        this.propagators = propagators == null ? List.of() : List.copyOf(propagators);
    }

    public AgentThreadContextPropagator.Snapshot captureAll() {
        List<AgentThreadContextPropagator.Snapshot> captured = new ArrayList<>(propagators.size());
        for (AgentThreadContextPropagator propagator : propagators) {
            if (propagator == null) {
                continue;
            }
            AgentThreadContextPropagator.Snapshot snapshot = propagator.capture();
            if (snapshot != null) {
                captured.add(snapshot);
            }
        }
        return () -> attachAll(captured);
    }

    private static AutoCloseable attachAll(List<AgentThreadContextPropagator.Snapshot> snapshots) {
        List<AutoCloseable> handles = new ArrayList<>(snapshots.size());
        for (AgentThreadContextPropagator.Snapshot snapshot : snapshots) {
            try {
                AutoCloseable handle = snapshot.attach();
                if (handle != null) {
                    handles.add(handle);
                }
            } catch (Exception ex) {
                closeReverse(handles);
                throw ex;
            }
        }
        return () -> closeReverse(handles);
    }

    private static void closeReverse(List<AutoCloseable> handles) {
        List<AutoCloseable> reverse = new ArrayList<>(handles);
        Collections.reverse(reverse);
        RuntimeException failure = null;
        for (AutoCloseable handle : reverse) {
            try {
                handle.close();
            } catch (Exception ex) {
                if (failure == null) {
                    failure = ex instanceof RuntimeException runtime ? runtime : new IllegalStateException(ex);
                } else {
                    failure.addSuppressed(ex);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
