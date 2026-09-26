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
        // Hibernate does not reliably widen an existing VARCHAR column to TEXT on PostgreSQL.
        // Do this before encryption, whose Base64 envelope can exceed the old 500-character limit.
        String product = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                connection -> connection.getMetaData().getDatabaseProductName());
        if ("PostgreSQL".equals(product)) {
            Boolean needsWidening = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                    + "WHERE table_schema = current_schema() AND table_name = 'novels' "
                    + "AND column_name = 'webdav_password' AND data_type <> 'text')", Boolean.class);
            if (Boolean.TRUE.equals(needsWidening)) jdbc.execute("ALTER TABLE novels ALTER COLUMN webdav_password TYPE TEXT");
        }
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
