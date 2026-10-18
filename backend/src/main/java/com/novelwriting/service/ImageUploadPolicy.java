package com.novelwriting.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** Shared storage/backup size and allowlisted image formats; never decodes or rewrites pixels. */
public final class ImageUploadPolicy {
    public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final Map<String, String> MIME = Map.of(
        ".png", "image/png", ".jpg", "image/jpeg", ".jpeg", "image/jpeg",
        ".gif", "image/gif", ".webp", "image/webp", ".bmp", "image/bmp");
    public record Format(String extension, String mimeType) {}

    public static Format format(String filename, String contentType) {
        if (filename == null || filename.isBlank() || filename.length() > 255
            || filename.codePoints().anyMatch(c -> c < 32 || c == 127))
            throw new ImageUploadProblem(400, "IMAGE_FILENAME_INVALID", "图片文件名无效，请重新选择图片。");
        int dot = filename.lastIndexOf('.');
        String extension = dot >= 0 ? filename.substring(dot).toLowerCase(Locale.ROOT) : "";
        String expected = MIME.get(extension);
        String actual = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (actual.equals("image/x-ms-bmp")) actual = "image/bmp";
        if (expected == null || !expected.equals(actual))
            throw new ImageUploadProblem(415, "IMAGE_FORMAT_UNSUPPORTED", "请选择 PNG、JPG、JPEG、GIF、WebP 或 BMP 图片，文件格式须与扩展名一致。");
        return new Format(extension, expected);
    }

    public static void verifySignature(byte[] bytes, String mime) {
        boolean valid = switch (mime) {
            case "image/png" -> starts(bytes, 0, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            case "image/jpeg" -> starts(bytes, 0, new byte[]{(byte) 255, (byte) 216, (byte) 255});
            case "image/gif" -> starts(bytes, 0, ascii("GIF87a")) || starts(bytes, 0, ascii("GIF89a"));
            case "image/bmp" -> starts(bytes, 0, ascii("BM"));
            case "image/webp" -> starts(bytes, 0, ascii("RIFF")) && starts(bytes, 8, ascii("WEBP"));
            default -> false;
        };
        if (!valid) throw new ImageUploadProblem(415, "IMAGE_FORMAT_INVALID", "文件内容与图片格式不符，请重新导出为受支持的图片后上传。");
    }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static boolean starts(byte[] bytes, int offset, byte[] prefix) {
        if (bytes.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[offset + i] != prefix[i]) return false;
        return true;
    }
    private ImageUploadPolicy() {}
}
