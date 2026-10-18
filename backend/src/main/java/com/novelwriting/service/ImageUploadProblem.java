package com.novelwriting.service;

/** Only fixed application-owned upload diagnostics are exposed to the browser. */
public final class ImageUploadProblem extends RuntimeException {
    private final int status;
    private final String code;
    public ImageUploadProblem(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static ImageUploadProblem tooLarge() {
        return new ImageUploadProblem(413, "IMAGE_TOO_LARGE", "图片不能超过 8 MiB，请压缩图片或选择较小的图片后重试。");
    }
}
