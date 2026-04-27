package com.neoproc.financialagent.worker.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;

public final class PortalDescriptorLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

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
            PortalDescriptor descriptor = YAML.readValue(in, PortalDescriptor.class);
            if (descriptor.id() == null || !descriptor.id().equals(portalId)) {
                throw new IllegalStateException(
                        "Descriptor id " + descriptor.id() + " does not match requested " + portalId);
            }
            return descriptor;
        }
    }
}
