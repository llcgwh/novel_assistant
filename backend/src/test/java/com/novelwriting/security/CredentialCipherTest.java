package com.novelwriting.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class CredentialCipherTest {
    @TempDir Path directory;
    CredentialCipher cipher() { return new CredentialCipher("", directory.resolve("private/webdav.key").toString()); }

    @Test void generatesPersistentKeyAndRandomizedCiphertext() throws Exception {
        CredentialCipher cipher = cipher();
        String first = cipher.encrypt("秘密-password"), second = cipher.encrypt("秘密-password");
        assertNotEquals(first, second);
        assertTrue(first.startsWith(CredentialCipher.PREFIX));
        assertFalse(first.contains("password"));
        assertEquals("秘密-password", cipher().decrypt(first)); // New process/provider reads same key.
        Path file = directory.resolve("private/webdav.key");
        assertEquals(32, Base64.getDecoder().decode(Files.readString(file)).length);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(file.getParent()));
        }
    }

    @Test void detectsTamperingAndWrongKeys() {
        String encrypted = cipher().encrypt("secret");
        byte[] payload = Base64.getDecoder().decode(encrypted.substring(CredentialCipher.PREFIX.length()));
        payload[payload.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> cipher().decrypt(CredentialCipher.PREFIX + Base64.getEncoder().encodeToString(payload)));
        CredentialCipher wrong = new CredentialCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "unused");
        assertThrows(IllegalStateException.class, () -> wrong.decrypt(encrypted));
    }

    @Test void missingKeyDoesNotGenerateReplacementWhenDecrypting() throws Exception {
        String encrypted = cipher().encrypt("secret");
        Files.delete(directory.resolve("private/webdav.key"));
        assertThrows(IllegalStateException.class, () -> cipher().decrypt(encrypted));
        assertFalse(Files.exists(directory.resolve("private/webdav.key")));
    }

    @Test void preservesLegacyAndEmptyValues() {
        CredentialCipher cipher = cipher();
        assertNull(cipher.encrypt(null)); assertEquals("", cipher.encrypt(""));
        assertEquals("legacy-secret", cipher.decrypt("legacy-secret"));
        assertFalse(Files.exists(directory.resolve("private/webdav.key")));
        assertEquals("enc:v1:literal-password", cipher.decrypt(cipher.encrypt("enc:v1:literal-password")));
    }

    @Test void rejectsInvalidConfigurationWithoutFallingBackToDisk() {
        CredentialCipher invalid = new CredentialCipher("invalid", directory.resolve("key").toString());
        assertThrows(IllegalStateException.class, () -> invalid.encrypt("secret"));
        assertFalse(Files.exists(directory.resolve("key")));
        assertThrows(IllegalStateException.class, () -> cipher().decrypt("enc:v2:unknown"));
    }
}
