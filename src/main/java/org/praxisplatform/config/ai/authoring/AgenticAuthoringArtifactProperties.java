package org.praxisplatform.config.ai.authoring;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "praxis.ai.authoring")
public class AgenticAuthoringArtifactProperties {

    private Path artifactsDir;
    private Path contractsDir;
    private String profileId = "create-minimal-form";
    private String minimalFormPlanSchema = "minimal-form-plan.v1.schema.json";
    private String examplesGovernanceManifest = "examples-governance-manifest.v0.json";
    private String pageCreateCatalog = "page-create-catalog.v0.json";
    private String compiledFormPatch = "compiled-form-patch.helpdesk-create-ticket.v0.json";
    private String authoringReplayBundle = "authoring-replay-bundle.helpdesk-create-ticket.v0.json";
    private boolean httpEnabled = false;
    private boolean dryRunEnabled = false;
    private Path reportPath;

    public Path getArtifactsDir() {
        return artifactsDir;
    }

    public void setArtifactsDir(Path artifactsDir) {
        this.artifactsDir = artifactsDir;
    }

    public Path getContractsDir() {
        return contractsDir;
    }

    public void setContractsDir(Path contractsDir) {
        this.contractsDir = contractsDir;
    }

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public String getMinimalFormPlanSchema() {
        return minimalFormPlanSchema;
    }

    public void setMinimalFormPlanSchema(String minimalFormPlanSchema) {
        this.minimalFormPlanSchema = minimalFormPlanSchema;
    }

    public String getExamplesGovernanceManifest() {
        return examplesGovernanceManifest;
    }

    public void setExamplesGovernanceManifest(String examplesGovernanceManifest) {
        this.examplesGovernanceManifest = examplesGovernanceManifest;
    }

    public String getPageCreateCatalog() {
        return pageCreateCatalog;
    }

    public void setPageCreateCatalog(String pageCreateCatalog) {
        this.pageCreateCatalog = pageCreateCatalog;
    }

    public String getCompiledFormPatch() {
        return compiledFormPatch;
    }

    public void setCompiledFormPatch(String compiledFormPatch) {
        this.compiledFormPatch = compiledFormPatch;
    }

    public String getAuthoringReplayBundle() {
        return authoringReplayBundle;
    }

    public void setAuthoringReplayBundle(String authoringReplayBundle) {
        this.authoringReplayBundle = authoringReplayBundle;
    }

    public boolean isHttpEnabled() {
        return httpEnabled;
    }

    public void setHttpEnabled(boolean httpEnabled) {
        this.httpEnabled = httpEnabled;
    }

    public boolean isDryRunEnabled() {
        return dryRunEnabled;
    }

    public void setDryRunEnabled(boolean dryRunEnabled) {
        this.dryRunEnabled = dryRunEnabled;
    }

    public Path getReportPath() {
        return reportPath;
    }

    public void setReportPath(Path reportPath) {
        this.reportPath = reportPath;
    }
}
