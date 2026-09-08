package com.novelwriting.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;

@Component
public class CredentialCipher {
    public static final String PREFIX = "enc:v1:";
    private final String configuredKey;
    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec cachedKey;

    public CredentialCipher(@Value("${NOVEL_CREDENTIAL_KEY:}") String configuredKey,
                            @Value("${NOVEL_CREDENTIAL_KEY_FILE:.secrets/webdav.key}") String keyFile) {
        this.configuredKey = configuredKey;
        this.keyFile = Path.of(keyFile).toAbsolutePath().normalize();
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(PREFIX.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt WebDAV credentials");
        }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return stored;
        if (!stored.startsWith(PREFIX)) {
            if (stored.startsWith("enc:")) throw new IllegalStateException("Unsupported credential encryption version");
            return stored; // Read legacy plaintext until the startup migration commits.
        }
        try {
            byte[] payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (payload.length < 28) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Arrays.copyOf(payload, 12)));
            cipher.updateAAD(PREFIX.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(payload, 12, payload.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot decrypt WebDAV credentials: key mismatch or damaged ciphertext");
        }
    }

    private synchronized SecretKeySpec key(boolean allowCreate) {
        if (cachedKey != null) return cachedKey;
        String encoded = configuredKey;
        if (encoded == null || encoded.isBlank()) {
            try {
                if (Files.isSymbolicLink(keyFile)) throw new IOException("Symbolic key file");
                if (!Files.exists(keyFile)) {
                    if (!allowCreate) throw new IllegalStateException("Credential key file is missing; restore the original key");
                    createKeyFile();
                }
                if (Files.size(keyFile) > 1024) throw new IOException("Invalid key file size");
                encoded = Files.readString(keyFile, StandardCharsets.US_ASCII).trim();
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read or create credential key file; check its location and permissions");
            }
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encoded.trim());
            if (raw.length != 32) throw new IllegalArgumentException();
            cachedKey = new SecretKeySpec(raw, "AES");
            return cachedKey;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Credential key must be Base64 encoding of exactly 32 random bytes");
        }
    }

    private void createKeyFile() throws IOException {
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        FileAttribute<?>[] directoryPermissions = posix
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))}
                : new FileAttribute<?>[0];
        Files.createDirectories(keyFile.getParent(), directoryPermissions);
        FileAttribute<?>[] filePermissions = posix
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        byte[] raw = new byte[32]; random.nextBytes(raw);
        try (SeekableByteChannel channel = Files.newByteChannel(keyFile,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), filePermissions)) {
            ByteBuffer bytes = ByteBuffer.wrap(Base64.getEncoder().encode(raw));
            while (bytes.hasRemaining()) channel.write(bytes);
        } catch (FileAlreadyExistsException ignored) {
            // Another process created it; read and validate that key, never overwrite it.
        }
    }
}
