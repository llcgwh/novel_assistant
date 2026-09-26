package com.novelwriting.maintenance;

import com.novelwriting.security.CredentialCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.util.*;

@Service
public class CredentialRotation {
    private final JdbcTemplate jdbc;
    private final CredentialCipher current;
    public CredentialRotation(JdbcTemplate jdbc, CredentialCipher current) { this.jdbc = jdbc; this.current = current; }
    private record Credential(long id, String plaintext) {}

    @Transactional(rollbackFor = Exception.class)
    public int rotate(Path newKey) throws Exception {
        newKey = newKey.toAbsolutePath().normalize();
        if (Files.exists(newKey, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("New key file must not exist; use a new path");
        List<Credential> rows = jdbc.query("SELECT id, webdav_password FROM novels WHERE webdav_password IS NOT NULL AND webdav_password <> '' FOR UPDATE",
            (rs, i) -> new Credential(rs.getLong(1), current.decrypt(rs.getString(2))));
        CredentialCipher replacement = new CredentialCipher("", newKey.toString());
        replacement.encrypt("key-validation"); // Creates a protected key file, even when no credentials exist.
        for (Credential row : rows)
            jdbc.update("UPDATE novels SET webdav_password = ? WHERE id = ?", replacement.encrypt(row.plaintext()), row.id());
        // Keep both keys. If the transaction fails the old one still works; if it commits use the new path.
        return rows.size();
    }
}
