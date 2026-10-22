package org.praxisplatform.config.service;

/** Reading a source does not grant copying or page creation. */
public enum PageCreationOperation {
  CREATE_PAGE,
  READ_CREATION,
  READ_SOURCE,
  COPY_SOURCE
}
