package com.neoproc.financialagent.worker.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PortalDescriptorLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    // Matches ${KEY} or ${KEY:default} where KEY is [A-Za-z0-9_] and default can contain anything except '}'
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)(?::([^}]*))?\\}");

    private PortalDescriptorLoader() {}

    public static boolean exists(String portalId) {
        return PortalDescriptorLoader.class.getResource("/portals/" + portalId + ".yaml") != null;
    }

    public static PortalDescriptor load(String portalId) throws IOException {
        String path = "/portals/" + portalId + ".yaml";
        try (InputStream in = PortalDescriptorLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("Portal descriptor not found: " + path);
            }
            String yaml = resolvePlaceholders(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            PortalDescriptor descriptor = YAML.readValue(yaml, PortalDescriptor.class);
            if (descriptor.id() == null || !descriptor.id().equals(portalId)) {
                throw new IllegalStateException(
                        "Descriptor id " + descriptor.id() + " does not match requested " + portalId);
            }
            return descriptor;
        }
    }

    // Resolves ${KEY:default} placeholders against env vars then JVM system properties.
    // Leaves unresolvable placeholders with no default unchanged.
    private static String resolvePlaceholders(String text) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key      = m.group(1);
            String fallback = m.group(2); // null when no ':default' present
            String value    = System.getenv(key);
            if (value == null || value.isBlank()) value = System.getProperty(key);
            if (value == null || value.isBlank()) value = fallback != null ? fallback : m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
