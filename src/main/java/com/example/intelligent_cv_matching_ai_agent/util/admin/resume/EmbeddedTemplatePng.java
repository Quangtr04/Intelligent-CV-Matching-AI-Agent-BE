package com.example.intelligent_cv_matching_ai_agent.util.admin.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Only bounded, static PNG raster data can be embedded; no resource URLs or active image formats are accepted. */
public final class EmbeddedTemplatePng {

    public static final int MAX_BYTES = 200000;

    public static final int MAX_DIMENSION = 2048;

    public static final long MAX_PIXELS = 4000000;

    private static final String PREFIX = "data:image/png;base64,";

    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private static final Set<String> CHUNKS = Set.of("IHDR", "PLTE", "tRNS", "IDAT", "IEND");

    private EmbeddedTemplatePng() {
    }

    public static String dataUri(
        byte[] png) {
        validateBytes(png);
        return PREFIX + Base64.getEncoder().encodeToString(png);
    }

    public static void validate(
        String dataUri) {
        if (dataUri == null || !dataUri.startsWith(PREFIX)
            || dataUri.length() > PREFIX.length() + 4 * ((MAX_BYTES + 2) / 3)) {
            throw invalid();
        }
        String encoded = dataUri.substring(PREFIX.length());
        if (encoded.isEmpty() || encoded.length() % 4 != 0 || !encoded.matches("[A-Za-z0-9+/]+={0,2}")) {
            throw invalid();
        }
        try {
            byte[] png = Base64.getDecoder().decode(encoded);
            if (!Base64.getEncoder().encodeToString(png).equals(encoded)) {
                throw invalid();
            }
            validateBytes(png);
        } catch (IllegalArgumentException error) {
            throw invalid();
        }
    }

    private static void validateBytes(
        byte[] png) {
        if (png == null || png.length < 57 || png.length > MAX_BYTES
            || !Arrays.equals(Arrays.copyOf(png, SIGNATURE.length), SIGNATURE)) {
            throw invalid();
        }
        int cursor = SIGNATURE.length;
        int width = 0;
        int height = 0;
        boolean hasData = false;
        boolean ended = false;
        while (cursor <= png.length - 12) {
            int length = ByteBuffer.wrap(png, cursor, 4).getInt();
            if (length < 0 || length > png.length - cursor - 12) {
                throw invalid();
            }
            String type = new String(png, cursor + 4, 4, StandardCharsets.US_ASCII);
            if (!CHUNKS.contains(type) || (cursor == SIGNATURE.length) != "IHDR".equals(type)) {
                throw invalid();
            }
            CRC32 checksum = new CRC32();
            checksum.update(png, cursor + 4, length + 4);
            long declaredChecksum = Integer.toUnsignedLong(ByteBuffer.wrap(png, cursor + 8 + length, 4).getInt());
            if (checksum.getValue() != declaredChecksum) {
                throw invalid();
            }
            if ("IHDR".equals(type)) {
                if (length != 13) {
                    throw invalid();
                }
                width = ByteBuffer.wrap(png, cursor + 8, 4).getInt();
                height = ByteBuffer.wrap(png, cursor + 12, 4).getInt();
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                    || (long) width * height > MAX_PIXELS) {
                    throw invalid();
                }
            } else if ("IDAT".equals(type)) {
                hasData = true;
            } else if ("IEND".equals(type)) {
                if (length != 0 || !hasData || cursor + 12 != png.length) {
                    throw invalid();
                }
                ended = true;
            }
            cursor += length + 12;
        }
        if (!ended || cursor != png.length) {
            throw invalid();
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(png)) {
            var image = ImageIO.read(input);
            if (image == null || image.getWidth() != width || image.getHeight() != height) {
                throw invalid();
            }
            image.flush();
        } catch (IOException | RuntimeException error) {
            if (error instanceof ApiException exception) {
                throw exception;
            }
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return ApiException.badRequest(
            "htmlContent images must be complete, static embedded PNGs of at most 200000 bytes, "
                + "2048 pixels per dimension and 4000000 pixels total"
        );
    }
}
