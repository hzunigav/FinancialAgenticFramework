package com.neoproc.financialagent.worker.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Scrubs specified request-body fields from a HAR file based on the
 * {@link PortalDescriptor.SecurityContext}. Operates on the JSON on disk —
 * Playwright has already written it. Replaced values become
 * {@code [REDACTED]}.
 *
 * <p>Handles the two request body shapes Playwright emits:
 * <ul>
 *   <li>{@code application/x-www-form-urlencoded} — parses
 *       {@code postData.text} as key=value&amp;... and rewrites.</li>
 *   <li>{@code application/json} — replaces the named fields at any depth.</li>
 * </ul>
 * For other content types we strip {@code postData} entirely rather than
 * risk leaking a secret embedded in a format we do not parse.
 */
public final class HarScrubber {

    private static final String REDACTED = "[REDACTED]";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<CompiledRule> rules;

    public HarScrubber(PortalDescriptor.SecurityContext securityContext) {
        this.rules = compile(securityContext);
    }

    public void scrub(Path harFile) throws IOException {
        if (rules.isEmpty() || !Files.exists(harFile)) {
            return;
        }
        JsonNode root = JSON.readTree(harFile.toFile());
        JsonNode entries = root.path("log").path("entries");
        if (!entries.isArray()) {
            return;
        }
        for (JsonNode entryNode : (ArrayNode) entries) {
            if (!(entryNode instanceof ObjectNode entry)) continue;
            JsonNode request = entry.path("request");
            if (!(request instanceof ObjectNode reqObj)) continue;

            String url = reqObj.path("url").asText("");
            List<CompiledRule> matched = matchingRules(url);
            if (matched.isEmpty()) continue;

            JsonNode postData = reqObj.path("postData");
            if (!(postData instanceof ObjectNode postObj)) continue;

            scrubPostData(postObj, matched);
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(harFile.toFile(), root);
    }

    private List<CompiledRule> matchingRules(String url) {
        List<CompiledRule> matched = new ArrayList<>();
        for (CompiledRule rule : rules) {
            if (rule.matches(url)) matched.add(rule);
        }
        return matched;
    }

    private void scrubPostData(ObjectNode postData, List<CompiledRule> matched) {
        List<String> fields = collectFields(matched);
        String mimeType = postData.path("mimeType").asText("").toLowerCase();
        if (mimeType.startsWith("application/x-www-form-urlencoded")) {
            scrubFormUrlEncoded(postData, fields);
        } else if (mimeType.contains("json")) {
            scrubJsonBody(postData, fields);
        } else {
            postData.put("text", REDACTED);
            postData.remove("params");
        }
    }

    private static List<String> collectFields(List<CompiledRule> matched) {
        List<String> all = new ArrayList<>();
        for (CompiledRule rule : matched) all.addAll(rule.fields());
        return all;
    }

    private static void scrubFormUrlEncoded(ObjectNode postData, List<String> fields) {
        JsonNode paramsNode = postData.path("params");
        if (paramsNode instanceof ArrayNode params) {
            for (JsonNode p : params) {
                if (p instanceof ObjectNode pObj) {
                    String name = pObj.path("name").asText("");
                    if (fields.contains(name)) {
                        pObj.set("value", TextNode.valueOf(REDACTED));
                    }
                }
            }
        }
        String text = postData.path("text").asText(null);
        if (text != null) {
            postData.put("text", rewriteFormUrlEncoded(text, fields));
        }
    }

    private static String rewriteFormUrlEncoded(String raw, List<String> fields) {
        if (raw.isEmpty()) return raw;
        Map<String, String> kv = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            String decodedKey = URLDecoder.decode(k, StandardCharsets.UTF_8);
            String finalValue = fields.contains(decodedKey)
                    ? URLEncoder.encode(REDACTED, StandardCharsets.UTF_8)
                    : v;
            kv.put(k, finalValue);
        }
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : kv.entrySet()) {
            if (!first) out.append('&');
            first = false;
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        return out.toString();
    }

    private static void scrubJsonBody(ObjectNode postData, List<String> fields) {
        String text = postData.path("text").asText(null);
        if (text == null || text.isEmpty()) return;
        try {
            JsonNode body = JSON.readTree(text);
            redactFieldsRecursively(body, fields);
            postData.put("text", JSON.writeValueAsString(body));
        } catch (IOException e) {
            postData.put("text", REDACTED);
        }
    }

    private static void redactFieldsRecursively(JsonNode node, List<String> fields) {
        if (node instanceof ObjectNode obj) {
            Iterator<String> it = obj.fieldNames();
            List<String> names = new ArrayList<>();
            while (it.hasNext()) names.add(it.next());
            for (String name : names) {
                if (fields.contains(name)) {
                    obj.set(name, TextNode.valueOf(REDACTED));
                } else {
                    redactFieldsRecursively(obj.get(name), fields);
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode child : arr) redactFieldsRecursively(child, fields);
        }
    }

    private static List<CompiledRule> compile(PortalDescriptor.SecurityContext ctx) {
        if (ctx == null) return List.of();
        List<CompiledRule> compiled = new ArrayList<>();
        for (PortalDescriptor.SecurityContext.HarScrubRule rule : ctx.scrubHarFields()) {
            Pattern regex = Pattern.compile(globToRegex(rule.urlPattern()));
            compiled.add(new CompiledRule(regex, rule.urlPattern(), rule.bodyFields()));
        }
        return List.copyOf(compiled);
    }

    /**
     * Convert a URL glob ({@code **} = any chars, {@code *} = any chars
     * except {@code /}, {@code ?} = single char) to an anchored regex.
     * All other characters are escaped so they match literally.
     */
    static String globToRegex(String glob) {
        StringBuilder out = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    out.append(".*");
                    i++;
                } else {
                    out.append("[^/]*");
                }
            } else if (c == '?') {
                out.append('.');
            } else if ("\\.+()[]{}|^$".indexOf(c) >= 0) {
                out.append('\\').append(c);
            } else {
                out.append(c);
            }
        }
        return out.append('$').toString();
    }

    private record CompiledRule(Pattern regex, String pattern, List<String> fields) {
        boolean matches(String url) {
            return regex.matcher(url).matches();
        }
    }
}
