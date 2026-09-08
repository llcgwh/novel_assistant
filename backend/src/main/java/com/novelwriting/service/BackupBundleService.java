package com.novelwriting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelwriting.entity.Image;
import com.novelwriting.entity.Novel;
import com.novelwriting.repository.ImageRepository;
import com.novelwriting.repository.NovelRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

@Service
public class BackupBundleService {
    public static final int MAX_BACKUP_BYTES = 48 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 32 * 1024 * 1024;
    private static final String FORMAT = "novel-assistant-images-v1";
    private static final Map<String, String> EXTENSIONS = Map.of("image/png", ".png", "image/jpeg", ".jpg",
            "image/gif", ".gif", "image/webp", ".webp", "image/bmp", ".bmp");
    private static final Pattern IMAGE_URL = Pattern.compile("^(?:https?://[^/]+)?/api/novels/(\\d+)/images/(\\d+)/file(?:[?#].*)?$");
    private static final Set<String> IMAGE_FIELDS = Set.of("coverImage", "portraitImage", "sceneImage", "locationImage", "entryImage");
    @Autowired private ExportService exporter;
    @Autowired private ImportService importer;
    @Autowired private ImageRepository images;
    @Autowired private NovelRepository novels;
    @Value("${app.upload.dir:uploads}") private String uploadDir;
    private final ObjectMapper mapper = new ObjectMapper();

    @Transactional(readOnly = true)
    public byte[] exportBundle(Long novelId) throws Exception {
        ObjectNode bundle = mapper.createObjectNode();
        bundle.put("format", FORMAT);
        ObjectNode data = (ObjectNode) mapper.readTree(exporter.exportNovelToJson(novelId));
        bundle.set("data", data);
        var assets = bundle.putArray("images");
        Map<Long, Long> ids = new HashMap<>();
        int total = 0;
        List<Image> records = images.findByNovelId(novelId);
        records.sort(Comparator.comparing(Image::getId));
        if (records.size() > 1000) throw new IOException("Too many images in backup");
        for (Image record : records) {
            Path base = Path.of(uploadDir).toRealPath();
            Path file = Path.of(record.getFilePath()).toRealPath();
            if (!file.startsWith(base.resolve(novelId.toString()))) throw new IOException("Image outside novel upload directory");
            byte[] bytes;
            try (InputStream stream = Files.newInputStream(file)) { bytes = readLimited(stream, MAX_IMAGE_BYTES); }
            if (bytes.length == 0) throw new IOException("Cannot back up an empty image");
            total += bytes.length;
            if (total > MAX_TOTAL_BYTES) throw new IOException("Images exceed 32 MiB backup limit");
            extension(record.getMimeType());
            ObjectNode asset = assets.addObject();
            asset.put("id", record.getId());
            asset.put("originalName", record.getOriginalName() == null ? record.getFilename() : record.getOriginalName());
            asset.put("mimeType", record.getMimeType());
            asset.put("imageType", record.getImageType() == null ? "other" : record.getImageType());
            asset.put("content", Base64.getEncoder().encodeToString(bytes));
            asset.put("sha256", digest(bytes));
            ids.put(record.getId(), record.getId());
        }
        rewriteImages(data, novelId, novelId, ids);
        byte[] result = mapper.writeValueAsBytes(bundle);
        if (result.length > MAX_BACKUP_BYTES) throw new IOException("Backup exceeds 48 MiB limit");
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void restore(Long novelId, byte[] bytes) throws Exception {
        if (bytes.length > MAX_BACKUP_BYTES) throw new IOException("Backup exceeds 48 MiB limit");
        JsonNode bundle = mapper.readTree(bytes);
        if (bundle == null || !bundle.has("format")) {
            importer.importFromJson(novelId, bytes); // Legacy data-only backup.
            return;
        }
        if (!FORMAT.equals(bundle.path("format").asText())) throw new IOException("Unsupported image backup format");
        JsonNode rawData = bundle.path("data");
        BackupValidator.validate(rawData);
        ObjectNode data = (ObjectNode) rawData;
        long sourceId = data.path("novel").path("id").asLong();
        if (sourceId <= 0) throw new IOException("Missing source novel ID");
        JsonNode assets = bundle.path("images");
        if (!assets.isArray() || assets.size() > 1000) throw new IOException("Invalid image manifest");
        Map<Long, byte[]> decoded = new LinkedHashMap<>();
        Map<Long, Long> oldIds = new HashMap<>();
        int total = 0;
        for (JsonNode asset : assets) {
            JsonNode idNode = asset.path("id");
            long id = idNode.asLong();
            if (!idNode.isIntegralNumber() || !idNode.canConvertToLong() || id <= 0 || decoded.containsKey(id)) throw new IOException("Invalid image ID");
            extension(text(asset, "mimeType", 100));
            text(asset, "originalName", 255); text(asset, "imageType", 50);
            String content = text(asset, "content", (MAX_IMAGE_BYTES + 2) / 3 * 4);
            byte[] image = Base64.getDecoder().decode(content);
            total += image.length;
            if (image.length == 0 || image.length > MAX_IMAGE_BYTES || total > MAX_TOTAL_BYTES) throw new IOException("Image size limit exceeded");
            if (!digest(image).equals(asset.path("sha256").asText())) throw new IOException("Image checksum mismatch");
            decoded.put(id, image); oldIds.put(id, id);
        }
        rewriteImages(data, sourceId, sourceId, oldIds); // Reject dangling image references before writes.
        novels.findById(novelId).orElseThrow(() -> new IOException("Novel not found"));
        Path root = Path.of(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path folder = root.resolve(novelId.toString());
        Files.createDirectories(folder);
        if (!folder.toRealPath().equals(root.toRealPath().resolve(novelId.toString()))) throw new IOException("Unsafe upload directory");
        List<Path> created = new ArrayList<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    for (Path file : created) {
                        try { Files.deleteIfExists(file); }
                        catch (IOException e) { org.slf4j.LoggerFactory.getLogger(BackupBundleService.class).warn("Cannot clean restored image {}", file, e); }
                    }
                }
            }
        });
        // Keep old files on disk for recovery; replace their database records transactionally.
        images.deleteAll(images.findByNovelId(novelId));
        images.flush();
        Map<Long, Long> newIds = new HashMap<>();
        for (JsonNode asset : assets) {
            long oldId = asset.get("id").asLong();
            byte[] imageBytes = decoded.get(oldId);
            String filename = UUID.randomUUID() + extension(asset.get("mimeType").asText());
            Path file = folder.resolve(filename);
            // Track only files we created, including partial writes on I/O failure.
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                created.add(file); out.write(imageBytes);
            }
            Image image = new Image();
            image.setNovelId(novelId); image.setFilename(filename); image.setFilePath(file.toString());
            image.setOriginalName(asset.get("originalName").asText()); image.setMimeType(asset.get("mimeType").asText());
            image.setImageType(asset.get("imageType").asText()); image.setFileSize(imageBytes.length);
            newIds.put(oldId, images.save(image).getId());
        }
        rewriteImages(data, sourceId, novelId, newIds);
        importer.importFromJson(novelId, mapper.writeValueAsBytes(data));
        Novel novel = novels.findById(novelId).orElseThrow();
        JsonNode metadata = data.path("novel");
        novel.setTitle(metadata.get("title").asText());
        novel.setDescription(metadata.path("description").isTextual() ? metadata.get("description").asText() : null);
        novel.setAuthor(metadata.path("author").isTextual() ? metadata.get("author").asText() : null);
        novel.setGenre(metadata.path("genre").isTextual() ? metadata.get("genre").asText() : null);
        novel.setCoverImage(metadata.path("coverImage").isTextual() ? metadata.get("coverImage").asText() : null);
        if (metadata.path("status").isTextual()) novel.setStatus(metadata.get("status").asText());
        novels.save(novel); // Destination WebDAV credentials stay unchanged.
    }

    private void rewriteImages(JsonNode node, long sourceId, long targetId, Map<Long, Long> ids) throws IOException {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (IMAGE_FIELDS.contains(field.getKey()) && field.getValue().isTextual()) {
                    Matcher match = IMAGE_URL.matcher(field.getValue().asText());
                    if (match.matches()) {
                        long imageId = Long.parseLong(match.group(2));
                        if (Long.parseLong(match.group(1)) != sourceId || !ids.containsKey(imageId)) throw new IOException("Missing or foreign image reference");
                        ((ObjectNode) node).put(field.getKey(), "/api/novels/" + targetId + "/images/" + ids.get(imageId) + "/file");
                    }
                } else rewriteImages(field.getValue(), sourceId, targetId, ids);
            }
        } else if (node.isArray()) for (JsonNode child : node) rewriteImages(child, sourceId, targetId, ids);
    }

    public static byte[] readLimited(InputStream stream, int max) throws IOException {
        byte[] bytes = stream.readNBytes(max + 1);
        if (bytes.length > max) throw new IOException("Backup or image size limit exceeded");
        return bytes;
    }
    private static String text(JsonNode node, String field, int max) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().length() > max) throw new IOException("Invalid image field: " + field);
        return value.asText();
    }
    private static String extension(String mime) throws IOException {
        String extension = EXTENSIONS.get(mime);
        if (extension == null) throw new IOException("Unsupported image MIME type");
        return extension;
    }
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
