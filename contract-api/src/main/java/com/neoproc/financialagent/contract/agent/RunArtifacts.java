package com.neoproc.financialagent.contract.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Where a run's output landed, so a consumer can fetch documents without
 * reconstructing the run-directory layout.
 *
 * @param folder {@code s3://…/<runId>/} with trailing slash, or null when no
 *               artifacts bucket is configured or the upload failed — output
 *               then exists only on the worker's disk and is not retrievable
 * @param files  every uploaded object, operational artifacts included
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunArtifacts(String folder, List<ArtifactRef> files) {

    public RunArtifacts {
        files = files == null ? List.of() : List.copyOf(files);
    }

    public static RunArtifacts none() {
        return new RunArtifacts(null, List.of());
    }
}
