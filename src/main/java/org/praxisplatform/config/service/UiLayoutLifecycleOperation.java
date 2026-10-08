package org.praxisplatform.config.service;

/** Admission is operation-specific; authoring is not approval or publication. */
public enum UiLayoutLifecycleOperation {
  READ_DRAFT, READ_REVIEW, READ_HEAD, READ_HISTORICAL_EVIDENCE, DIAGNOSE_EVOLUTION,
  CREATE_DRAFT, EDIT_DRAFT, SUBMIT_RELEASE, APPROVE_RELEASE, PUBLISH_RELEASE, WITHDRAW_RELEASE, ROLLBACK_RELEASE
}
