package com.novelwriting.maintenance;

import com.novelwriting.entity.Novel;
import com.novelwriting.security.CredentialCipher;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({com.novelwriting.security.TestCredentials.class, CredentialRotation.class})
class CredentialRotationTest {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired CredentialRotation rotation;
    @Autowired CredentialCipher oldCipher;
    @TempDir Path root;
    @Test void rotatesEncryptedRowsAndPreservesOriginalKey() throws Exception {
        Novel n = new Novel(); n.setTitle("Rotate"); n.setWebdavPassword("密钥测试"); em.persist(n); em.flush();
        String before = jdbc.queryForObject("select webdav_password from novels where id=?", String.class, n.getId());
        Path next = root.resolve("next.key"); rotation.rotate(next);
        String after = jdbc.queryForObject("select webdav_password from novels where id=?", String.class, n.getId());
        assertEquals("密钥测试", oldCipher.decrypt(before));
        assertEquals("密钥测试", new CredentialCipher("", next.toString()).decrypt(after));
        assertThrows(IllegalStateException.class, () -> oldCipher.decrypt(after));
        assertThrows(IllegalArgumentException.class, () -> rotation.rotate(next));
    }
}
