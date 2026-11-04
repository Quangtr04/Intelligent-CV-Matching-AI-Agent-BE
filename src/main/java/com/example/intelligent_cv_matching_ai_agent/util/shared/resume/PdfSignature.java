package com.example.intelligent_cv_matching_ai_agent.util.shared.resume;

public final class PdfSignature {

    private static final int MAX_PREFIX_BYTES = 1024;

    private static final byte[] HEADER = {'%', 'P', 'D', 'F', '-'};

    private PdfSignature() {
    }

    public static boolean isValid(
        byte[] bytes) {
        if (bytes == null) {
            return false;
        }

        int offset =
            bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf ? 3 : 0;

        // Some exporters prepend a BOM or blank lines. Keep the original bytes for PDF cross-reference offsets.
        while (offset < bytes.length && offset <= MAX_PREFIX_BYTES && isWhitespace(bytes[offset])) {
            offset++;
        }
        if (offset > MAX_PREFIX_BYTES || bytes.length - offset < HEADER.length) {
            return false;
        }
        for (int index = 0; index < HEADER.length; index++) {
            if (bytes[offset + index] != HEADER[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWhitespace(
        byte value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f';
    }
}
