package org.praxisplatform.config.projection;

/** Minimal database projection used to verify authoring-manifest snapshot drift. */
public interface AiRegistryAuthoringManifestProjection {

    String getRegistryKey();

    String getAuthoringManifest();
}
