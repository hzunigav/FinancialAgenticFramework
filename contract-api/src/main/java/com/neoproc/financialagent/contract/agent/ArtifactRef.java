package com.neoproc.financialagent.contract.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One object a run wrote to S3.
 *
 * @param name  path relative to the run folder, e.g.
 *              {@code reports/PlanillaNeoprocSociedadAnonima082026.pdf}
 * @param uri   fully-qualified {@code s3://} location, directly fetchable
 * @param bytes object size; -1 when it could not be read
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ArtifactRef(String name, String uri, long bytes) {}
