package com.neoproc.financialagent.contract.bankstatement;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Cleartext body of {@code bank-statement-upload-request.v1} (the
 * {@code request} field). Carries the CSV bytes. Exactly one of
 * {@link FileRef#inline()} / {@link FileRef#s3()} is non-null — inline
 * base64 is the day-one transport; {@code s3} is reserved for the future
 * large-file path (see design §6).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileBody(FileRef file) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileRef(
            String contentType,
            String encoding,
            String sha256,
            String inline,
            S3Ref s3) {

        /** Schema const for {@code file.contentType}. */
        public static final String CONTENT_TYPE = "text/csv";
        /** Schema const for {@code file.encoding}. */
        public static final String ENCODING = "base64";

        /** Inline-transport factory: the day-one path. */
        public static FileRef inline(String sha256, String base64Csv) {
            return new FileRef(CONTENT_TYPE, ENCODING, sha256, base64Csv, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record S3Ref(String bucket, String key, String presignedUrl, String expiresAt) {}
}
