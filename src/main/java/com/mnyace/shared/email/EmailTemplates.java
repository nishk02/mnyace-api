package com.mnyace.shared.email;

import org.springframework.core.io.ClassPathResource;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fills the HTML files in resources/email; placeholders are {{params.NAME}}, the same syntax Brevo templates use. */
public final class EmailTemplates {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{params\\.([A-Z_]+)}}");
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private EmailTemplates() {
    }

    /** Values are HTML-escaped. A placeholder without a value, or a value without a placeholder, is an error, so the two cannot drift apart. */
    public static String render(String template, Map<String, String> params) {
        String html = CACHE.computeIfAbsent(template, EmailTemplates::load);

        Set<String> used = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(html);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = params.get(name);
            if (value == null) {
                throw new IllegalArgumentException("Email template '" + template + "' needs a value for " + name);
            }
            used.add(name);
            matcher.appendReplacement(out, Matcher.quoteReplacement(HtmlUtils.htmlEscape(value)));
        }
        matcher.appendTail(out);

        Set<String> unused = new TreeSet<>(params.keySet());
        unused.removeAll(used);
        if (!unused.isEmpty()) {
            throw new IllegalArgumentException("Email template '" + template + "' has no placeholder for " + unused);
        }
        return out.toString();
    }

    private static String load(String template) {
        try {
            return new ClassPathResource("email/" + template + ".html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Email template not found: " + template, e);
        }
    }
}
