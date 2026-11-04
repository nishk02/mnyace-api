package com.mnyace.shared.health;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The proxy's error reply must allow exactly the origins the API allows in prod, or the two drift apart silently. */
class ProxyCorsOriginsTest {
    @Test
    void theCaddyfileListsTheSameOriginsAsTheProdApi() throws IOException {
        Set<String> api = prodApiOrigins();
        String caddyfile = Files.readString(Path.of("Caddyfile"));

        // both matchers (the reply and the preflight) carry the list: ["a", "b", "c"]
        Matcher lists = Pattern.compile("\\{http\\.request\\.header\\.Origin} in \\[([^\\]]*)]").matcher(caddyfile);
        int found = 0;
        while (lists.find()) {
            found++;
            Set<String> proxy = Arrays.stream(lists.group(1).split(","))
                    .map(s -> s.trim().replace("\"", ""))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            assertThat(proxy).isEqualTo(api);
        }
        assertThat(found).as("origin lists found in the Caddyfile").isEqualTo(2);
    }

    private static Set<String> prodApiOrigins() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("src/main/resources/application-prod.properties"));
        String line = lines.stream().filter(l -> l.startsWith("cors.allowed-origins=")).findFirst().orElseThrow();
        return Arrays.stream(line.substring("cors.allowed-origins=".length()).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }
}
