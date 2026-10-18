package com.novelwriting.controller;

import com.novelwriting.service.ImageUploadProblem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Multipart parsing can fail before a controller is selected, so this advice is global. */
@RestControllerAdvice
public class ImageUploadExceptionHandler {
    private static final ObjectMapper JSON = new ObjectMapper();
    @ExceptionHandler(ImageUploadProblem.class)
    public ResponseEntity<?> problem(ImageUploadProblem problem) {
        byte[] body = JSON.valueToTree(Map.of("code", problem.code(), "message", problem.getMessage())).toString().getBytes(StandardCharsets.UTF_8);
        // An oversized request can exhaust Tomcat's bounded swallow allowance. A known
        // response length avoids depending on a final chunk after the connection closes.
        return ResponseEntity.status(problem.status()).contentType(MediaType.APPLICATION_JSON).contentLength(body.length).body(body);
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge() { return problem(ImageUploadProblem.tooLarge()); }
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<?> missingPart() {
        return problem(new ImageUploadProblem(400, "IMAGE_FILE_REQUIRED", "请选择需要上传的图片文件。"));
    }
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> invalidMultipart() {
        return problem(new ImageUploadProblem(400, "IMAGE_MULTIPART_INVALID", "图片上传请求不完整或格式无效，请重新选择图片后重试。"));
    }
}
