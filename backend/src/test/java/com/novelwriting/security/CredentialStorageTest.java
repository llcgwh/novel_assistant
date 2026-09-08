package com.novelwriting.security;

import com.novelwriting.entity.Novel;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({TestCredentials.class, CredentialMigration.class})
class CredentialStorageTest {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired CredentialMigration migration;
    @Autowired CredentialCipher cipher;

    Novel novel(String password) {
        Novel novel = new Novel(); novel.setTitle("Test"); novel.setWebdavPassword(password);
        em.persist(novel); em.flush(); return novel;
    }
    String raw(Long id) { return jdbc.queryForObject("SELECT webdav_password FROM novels WHERE id=?", String.class, id); }

    @Test void databaseStoresOnlyCiphertextAndReadsBackLongUnicodePassword() {
        String password = "秘密".repeat(250);
        Long id = novel(password).getId();
        assertTrue(raw(id).startsWith(CredentialCipher.PREFIX));
        assertFalse(raw(id).contains(password));
        em.clear();
        assertEquals(password, em.find(Novel.class, id).getWebdavPassword());
    }

    @Test void migratesLegacyRowsAndIsIdempotent() {
        Long legacy = novel(null).getId(), encrypted = novel("already-encrypted").getId();
        jdbc.update("UPDATE novels SET webdav_password=? WHERE id=?", "legacy-secret", legacy);
        String originalCiphertext = raw(encrypted);
        em.clear();
        migration.run(null);
        String migrated = raw(legacy);
        assertTrue(migrated.startsWith(CredentialCipher.PREFIX));
        assertEquals("legacy-secret", cipher.decrypt(migrated));
        assertEquals(originalCiphertext, raw(encrypted));
        migration.run(null);
        assertEquals(migrated, raw(legacy));
        em.clear(); assertEquals("legacy-secret", em.find(Novel.class, legacy).getWebdavPassword());
    }

    @Test void wrongKeyOrDamagedCiphertextStopsMigrationBeforeAnyWrites() {
        Long legacy = novel(null).getId(), damaged = novel(null).getId();
        jdbc.update("UPDATE novels SET webdav_password=? WHERE id=?", "legacy-secret", legacy);
        jdbc.update("UPDATE novels SET webdav_password=? WHERE id=?", "enc:v1:damaged", damaged);
        assertThrows(IllegalStateException.class, () -> migration.run(null));
        assertEquals("legacy-secret", raw(legacy));
        assertEquals("enc:v1:damaged", raw(damaged));
    }

    @Test void ordinaryNovelUpdateAlsoEncryptsLegacyPassword() {
        Long id = novel(null).getId();
        jdbc.update("UPDATE novels SET webdav_password=? WHERE id=?", "legacy-secret", id);
        em.clear();
        Novel legacy = em.find(Novel.class, id);
        legacy.setTitle("Updated"); em.flush();
        assertTrue(raw(id).startsWith(CredentialCipher.PREFIX));
        assertEquals("legacy-secret", cipher.decrypt(raw(id)));
    }
}
