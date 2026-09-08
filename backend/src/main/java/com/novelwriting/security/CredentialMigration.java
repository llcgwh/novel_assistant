package com.novelwriting.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CredentialMigration implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    public CredentialMigration(JdbcTemplate jdbc, CredentialCipher cipher) { this.jdbc = jdbc; this.cipher = cipher; }
    private record Stored(Long id, String value) {}

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var rows = jdbc.query("SELECT id, webdav_password FROM novels WHERE webdav_password IS NOT NULL AND webdav_password <> ''",
                (rs, index) -> new Stored(rs.getLong(1), rs.getString(2)));
        // Check the existing key before generating a new key or migrating any plaintext.
        for (Stored row : rows) {
            if (row.value().startsWith("enc:")) cipher.decrypt(row.value());
        }
        for (Stored row : rows) {
            if (!row.value().startsWith("enc:")) {
                jdbc.update("UPDATE novels SET webdav_password = ? WHERE id = ? AND webdav_password = ?",
                        cipher.encrypt(row.value()), row.id(), row.value());
            }
        }
    }
}
