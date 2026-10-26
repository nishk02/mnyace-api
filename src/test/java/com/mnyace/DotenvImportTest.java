package com.mnyace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// Documents how the dev-only dotenv import reads a file: as .properties, so quotes are NOT stripped.
class DotenvImportTest {
    @TempDir
    Path dir;

    @Test
    void plainValuesLoad_andResolveAsPlaceholders() throws IOException {
        Path file = Files.writeString(dir.resolve("dotenv"), "DOTENV_IMPORT_TEST_KEY=sample-value\n");

        runner(file).run(context -> {
            assertThat(context.getEnvironment().getProperty("DOTENV_IMPORT_TEST_KEY")).isEqualTo("sample-value");
            assertThat(context.getEnvironment().resolvePlaceholders("${DOTENV_IMPORT_TEST_KEY}")).isEqualTo("sample-value");
        });
    }

    @Test
    void quotedValuesKeepTheirQuotes_whichBreaksSmtpLogins() throws IOException {
        Path file = Files.writeString(dir.resolve("dotenv"), "DOTENV_IMPORT_TEST_KEY=\"sample-value\"\n");

        runner(file).run(context ->
                assertThat(context.getEnvironment().getProperty("DOTENV_IMPORT_TEST_KEY")).isEqualTo("\"sample-value\""));
    }

    @Test
    void aMissingFileIsIgnored() {
        runner(dir.resolve("does-not-exist")).run(context ->
                assertThat(context.getEnvironment().getProperty("DOTENV_IMPORT_TEST_KEY")).isNull());
    }

    private ApplicationContextRunner runner(Path file) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.import=optional:file:" + file + "[.properties]");
    }
}
