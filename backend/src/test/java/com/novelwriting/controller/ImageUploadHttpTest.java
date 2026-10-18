package com.novelwriting.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelwriting.entity.Image;
import com.novelwriting.repository.ImageRepository;
import com.novelwriting.service.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/** Real multipart parser and Tomcat socket tests, with no database or user upload directory. */
@SpringBootTest(classes = ImageUploadHttpTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.banner-mode=off", "logging.level.root=WARN"})
class ImageUploadHttpTest {
    static final Path ROOT;
    static { try { ROOT = Files.createTempDirectory("novel-image-http-test-"); } catch (IOException e) { throw new ExceptionInInitializerError(e); } }
    static final ObjectMapper JSON = new ObjectMapper();
    static final String BOUNDARY = "novel-image-fixture-boundary";
    static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXs4AAAAASUVORK5CYII=");
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
    @Import({ImageController.class, ImageUploadExceptionHandler.class, ImageService.class})
    static class App {}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("app.upload.dir", ROOT::toString); }
    @LocalServerPort int port;
    @MockBean ImageRepository repository;
    @MockBean NovelScope scope;
    @Autowired ImageService imageService;
    @Autowired ImageController imageController;
    @Autowired MultipartProperties multipart;
    final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach void prepare() {
        reset(repository);
        AtomicLong ids = new AtomicLong();
        when(repository.saveAndFlush(any(Image.class))).thenAnswer(call -> { Image row = call.getArgument(0); row.setId(ids.incrementAndGet()); return row; });
    }
    @AfterEach void cleanupFiles() throws IOException {
        try (var paths = Files.walk(ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) if (!path.equals(ROOT)) Files.deleteIfExists(path);
        }
    }
    @AfterAll static void cleanupRoot() throws IOException { Files.deleteIfExists(ROOT); }

    @Test void productionLimitsAcceptImagesOverOneMiBAndExactlyEightMiB() throws Exception {
        assertEquals(ImageUploadPolicy.MAX_IMAGE_BYTES, multipart.getMaxFileSize().toBytes());
        assertEquals(9 * 1024 * 1024, multipart.getMaxRequestSize().toBytes());
        for (int size : new int[]{2 * 1024 * 1024, ImageUploadPolicy.MAX_IMAGE_BYTES}) {
            var response = upload(png(size), "portrait.png", "image/png", false);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode result = JSON.readTree(response.body());
            assertEquals(size, result.path("fileSize").asInt());
            assertEquals(size, Files.size(Path.of(result.path("filePath").asText())));
        }
        verify(repository, times(2)).saveAndFlush(any());
    }

    @Test void realParserReturnsChineseJsonForFileAndRequestSizeViolations() throws Exception {
        for (int size : new int[]{ImageUploadPolicy.MAX_IMAGE_BYTES + 1, 10 * 1024 * 1024, 20 * 1024 * 1024}) {
            var response = upload(png(size), "portrait.png", "image/png", false);
            assertProblem(response, 413, "IMAGE_TOO_LARGE");
            assertTrue(JSON.readTree(response.body()).path("message").asText().contains("8 MiB"));
        }
        verifyNoInteractions(repository); assertNoFiles();
    }

    @Test void unknownLengthChunkedUploadStillEnforcesFileLimit() throws Exception {
        assertProblem(upload(png(ImageUploadPolicy.MAX_IMAGE_BYTES + 1), "portrait.png", "image/png", true), 413, "IMAGE_TOO_LARGE");
        verifyNoInteractions(repository); assertNoFiles();
    }

    @Test void invalidImageFormatsAndEmptyOrMissingFilesHaveStructuredClientErrors() throws Exception {
        assertProblem(upload(PNG, "portrait.svg", "image/svg+xml", false), 415, "IMAGE_FORMAT_UNSUPPORTED");
        assertProblem(upload(PNG, "portrait.png", "image/jpeg", false), 415, "IMAGE_FORMAT_UNSUPPORTED");
        assertProblem(upload("not an image".getBytes(StandardCharsets.UTF_8), "portrait.png", "image/png", false), 415, "IMAGE_FORMAT_INVALID");
        assertProblem(upload(new byte[0], "portrait.png", "image/png", false), 400, "IMAGE_EMPTY");
        assertProblem(send(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8), "multipart/form-data; boundary=" + BOUNDARY, false), 400, "IMAGE_FILE_REQUIRED");
        assertProblem(send("broken".getBytes(StandardCharsets.UTF_8), "multipart/form-data", false), 400, "IMAGE_MULTIPART_INVALID");
        verifyNoInteractions(repository); assertNoFiles();
    }

    @Test void persistenceFailureReturnsSafeMessageAndLeavesNoFile() throws Exception {
        when(repository.saveAndFlush(any())).thenThrow(new IllegalStateException("private filesystem or database detail"));
        var response = upload(PNG, "portrait.png", "image/png", false);
        assertProblem(response, 500, "IMAGE_STORAGE_FAILED");
        assertFalse(response.body().contains("private")); assertNoFiles();
    }

    @Test void legacyBmpMimeIsNormalizedAndSupportedFormatsRemainCompatible() throws Exception {
        for (String[] format : new String[][]{{"photo.jpeg", "image/jpeg", "jpeg"}, {"photo.gif", "image/gif", "gif"}, {"photo.bmp", "image/x-ms-bmp", "bmp"}, {"photo.webp", "image/webp", "webp"}}) {
            byte[] bytes = switch (format[2]) {
                case "jpeg" -> new byte[]{(byte) 255, (byte) 216, (byte) 255, 0};
                case "gif" -> "GIF89a fixture".getBytes(StandardCharsets.US_ASCII);
                case "bmp" -> "BM fixture".getBytes(StandardCharsets.US_ASCII);
                default -> "RIFF0000WEBP fixture".getBytes(StandardCharsets.US_ASCII);
            };
            var response = upload(bytes, format[0], format[1], false);
            assertEquals(200, response.statusCode(), response.body());
            if (format[2].equals("bmp")) assertEquals("image/bmp", JSON.readTree(response.body()).path("mimeType").asText());
        }
    }

    @Test void uploadReadAndDeleteWorkThroughAliasRootForNewLegacyAndMissingFiles() throws Exception {
        Path alias = ROOT.resolve("alias"); Files.createSymbolicLink(alias, ROOT);
        ReflectionTestUtils.setField(imageService, "uploadDir", alias.toString());
        ReflectionTestUtils.setField(imageController, "uploadDir", alias.toString());
        try {
            var uploaded = upload(PNG, "portrait.png", "image/png", false);
            assertEquals(200, uploaded.statusCode(), uploaded.body());
            Image image = new Image(); image.setId(1L); image.setNovelId(1L); image.setMimeType("image/png"); image.setOriginalName("人物.png");
            image.setFilePath(JSON.readTree(uploaded.body()).path("filePath").asText());
            when(repository.findById(1L)).thenReturn(Optional.of(image));
            assertImageFileReads(PNG);
            assertEquals(200, client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/novels/1/images/1")).DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertFalse(Files.exists(Path.of(image.getFilePath())));
            Path legacy = alias.resolve("1/legacy.png"); Files.write(legacy, PNG); image.setFilePath(legacy.toString());
            assertImageFileReads(PNG);
            Files.delete(legacy);
            assertEquals(404, client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/novels/1/images/1/file")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/novels/1/images/1")).DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            verify(repository, times(2)).deleteById(1L);
        } finally {
            ReflectionTestUtils.setField(imageService, "uploadDir", ROOT.toString());
            ReflectionTestUtils.setField(imageController, "uploadDir", ROOT.toString());
            Files.deleteIfExists(alias);
        }
    }

    void assertImageFileReads(byte[] expected) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/novels/1/images/1/file")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode()); assertArrayEquals(expected, response.body());
    }

    HttpResponse<String> upload(byte[] bytes, String filename, String mime, boolean chunked) throws Exception {
        var body = new ByteArrayOutputStream();
        body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"imageType\"\r\n\r\ncharacter_portrait\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(body.toByteArray(), "multipart/form-data; boundary=" + BOUNDARY, chunked);
    }
    HttpResponse<String> send(byte[] body, String type, boolean chunked) throws Exception {
        var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)) : HttpRequest.BodyPublishers.ofByteArray(body);
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/novels/1/images"))
            .timeout(Duration.ofSeconds(30)).header("Content-Type", type).POST(publisher).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    static byte[] png(int size) { return Arrays.copyOf(PNG, size); }
    static void assertProblem(HttpResponse<String> response, int status, String code) throws IOException {
        assertEquals(status, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals(code, body.path("code").asText()); assertFalse(body.path("message").asText().isBlank());
    }
    static void assertNoFiles() throws IOException { try (var paths = Files.walk(ROOT)) { assertEquals(0, paths.filter(Files::isRegularFile).count()); } }
}
