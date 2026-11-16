package com.zirocraft.zirologistics.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

class IntegerDisplayWidthMigrationPolicyTest {

    private static final List<String> MIGRATION_LOCATIONS = List.of(
            "db/migration",
            "db/wms-migration");

    private static final Pattern VERSIONED_MIGRATION =
            Pattern.compile("^V(\\d+)__.*\\.sql$");

    private static final Pattern INTEGER_DISPLAY_WIDTH =
            Pattern.compile(
                    "\\b(?:TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|BIGINT)\\s*\\(\\s*\\d+\\s*\\)",
                    Pattern.CASE_INSENSITIVE);

    @Test
    void migrationsAfterFrozenV1MustNotIntroduceIntegerDisplayWidths() throws IOException {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<String> checkedMigrations = new ArrayList<>();

        for (String location : MIGRATION_LOCATIONS) {
            Resource[] resources = resolver.getResources(
                    ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + location + "/V*.sql");

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                Matcher versionMatcher = VERSIONED_MIGRATION.matcher(filename == null ? "" : filename);

                assertTrue(versionMatcher.matches(),
                        () -> "Unexpected migration filename: " + filename);

                int version = Integer.parseInt(versionMatcher.group(1));
                if (version <= 1) {
                    // V1 migrations are historical baselines and must not be rewritten.
                    continue;
                }

                String sql;
                try (var input = resource.getInputStream()) {
                    sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }

                checkedMigrations.add(resource.getURI().toString());
                assertFalse(INTEGER_DISPLAY_WIDTH.matcher(sql).find(),
                        () -> "New migration must not use deprecated integer display width: "
                                + resource.getFilename());
            }
        }

        assertFalse(checkedMigrations.isEmpty(),
                "Expected at least one post-V1 migration to enforce the policy.");
    }
}
