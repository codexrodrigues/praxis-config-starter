package org.praxisplatform.config.service;

import java.util.List;
import org.praxisplatform.config.domain.UiLayoutRelease;
import org.praxisplatform.config.domain.UiLayoutReleaseMember;

/** Component/schema owner seam; it validates fixed release members without matching audience. */
@FunctionalInterface
public interface UiLayoutReleaseValidator {
  void validate(UiLayoutRelease release, List<UiLayoutReleaseMember> members);

  static UiLayoutReleaseValidator denyAll() {
    return (release, members) -> {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.VALIDATION_FAILED,
          "No host component/schema validator is installed for UI layout publication.");
    };
  }
}
