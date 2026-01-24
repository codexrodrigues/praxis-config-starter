package org.praxisplatform.config;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

// This class is used to configure the Spring Boot application context for tests
// It explicitly excludes certain auto-configurations that require external AI service credentials.
@SpringBootApplication
@Import(TestAiConfig.class)
public class TestApplication {
}