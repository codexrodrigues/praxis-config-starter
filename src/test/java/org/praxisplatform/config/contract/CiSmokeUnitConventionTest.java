package org.praxisplatform.config.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@Tag("smoke")
class CiSmokeUnitConventionTest {

    private static final Pattern TEST_CLASSIFICATION_TAG =
            Pattern.compile("@Tag\\(\"(?:unit|smoke|integration|external|e2e)\"\\)");

    @Test
    void shouldKeepCiSmokeUnitProfileFilteringOnlyFastTests() throws Exception {
        Document pom = parseXml(resolveRepoFile("pom.xml"));
        Element profile = findProfileById(pom, "ci-smoke-unit");

        assertThat(textOfFirst(profile, "groups")).isEqualTo("unit,smoke");
        assertThat(textOfFirst(profile, "excludedGroups")).isEqualTo("integration,external,e2e");

        NodeList excludes = profile.getElementsByTagName("exclude");
        assertThat(excludes.getLength()).isGreaterThanOrEqualTo(1);
        assertThat(excludes.item(0).getTextContent().trim()).isEqualTo("**/*IntegrationTest.java");
    }

    @Test
    void shouldKeepReleaseWorkflowUsingCiSmokeUnitProfile() throws IOException {
        String workflow = Files.readString(resolveRepoFile(".github/workflows/release.yml"));

        assertThat(workflow).contains("name: CI and Release Java Starter (praxis-config-starter)");
        assertThat(workflow).doesNotContain("branches:", "pull_request:", "build_main:");
        assertThat(workflow).contains("workflow_dispatch:", "tags:", "- v*");
        assertThat(workflow).contains("mvn -B -P release,ci-smoke-unit");
        assertThat(workflow).doesNotContain("-DskipTests", "-Dmaven.test.skip=true");
        assertThat(workflow).contains("clean verify org.sonatype.central:central-publishing-maven-plugin:publish");
        assertThat(workflow.split("clean verify", -1)).hasSize(2);
    }

    @Test
    void shouldPersistReleaseVersionBeforeCreatingAnImmutableTag() throws IOException {
        String workflow = Files.readString(resolveRepoFile(".github/workflows/release.yml"));

        assertThat(workflow)
                .contains("token: ${{ secrets.RELEASE_PAT }}")
                .contains("mvn -q versions:set -DnewVersion=\"$VERSION\" -DgenerateBackupPoms=false")
                .contains("git commit -m \"chore: release $TAG\"")
                .contains("git tag -a \"$TAG\" -m \"Release $TAG\"")
                .contains("git push --atomic origin \"HEAD:${GITHUB_REF_NAME}\" \"$TAG\"")
                .contains("group: java-release-${{ github.event_name }}")
                .contains("git merge-base --is-ancestor HEAD origin/main")
                .contains("main advanced; prepare the new revision deliberately.")
                .contains("if: github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')")
                .contains("Tagged pom.xml version '$POM_VERSION' does not match tag version '$TAG_VERSION'.");
        assertThat(workflow)
                .doesNotContain("secrets.RELEASE_PAT || github.token")
                .doesNotContain("Tag '$TAG' already exists. Nothing to push.")
                .doesNotContain("github.event_name == 'workflow_dispatch' && inputs.create_tag == false")
                .doesNotContain("Manual publish requires the version input when create_tag=false.");
    }

    @Test
    void shouldRequireClassificationTagForEveryJUnitTestClass() throws IOException {
        Path testsRoot = resolveRepoFile("src/test/java");
        Map<String, String> missingTags = new LinkedHashMap<>();

        try (var paths = Files.walk(testsRoot)) {
            paths.filter(path -> path.getFileName().toString().endsWith("Test.java"))
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path);
                            if (!TEST_CLASSIFICATION_TAG.matcher(source).find()) {
                                missingTags.put(testsRoot.relativize(path).toString(), "missing @Tag classification");
                            }
                        } catch (IOException ex) {
                            throw new IllegalStateException("Failed to inspect test source " + path, ex);
                        }
                    });
        }

        assertThat(missingTags).isEmpty();
    }

    private static Document parseXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try (InputStream in = Files.newInputStream(path)) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static Element findProfileById(Document pom, String id) {
        NodeList profiles = pom.getElementsByTagName("profile");
        for (int i = 0; i < profiles.getLength(); i++) {
            Element profile = (Element) profiles.item(i);
            if (id.equals(textOfFirst(profile, "id"))) {
                return profile;
            }
        }
        throw new IllegalStateException("Profile not found: " + id);
    }

    private static String textOfFirst(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return nodes.item(0).getTextContent().trim();
    }

    private static Path resolveRepoFile(String relativePath) {
        Path cwd = Paths.get("").toAbsolutePath();
        List<Path> candidates = List.of(
                cwd.resolve(relativePath).normalize(),
                cwd.resolve("praxis-config-starter").resolve(relativePath).normalize(),
                cwd.resolve("..").resolve(relativePath).normalize(),
                cwd.resolve("../praxis-config-starter").resolve(relativePath).normalize());
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return candidates.get(0);
    }
}
