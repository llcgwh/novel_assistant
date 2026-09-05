package com.novelwriting.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NovelPrivacyTest {
    @Test void passwordIsAbsentFromApiBackupAndLogs() throws Exception {
        Novel novel = new Novel();
        novel.setTitle("Test novel");
        novel.setWebdavPassword("test-secret");
        ObjectMapper mapper = new ObjectMapper();
        String apiJson = mapper.writeValueAsString(novel);
        String backupJson = mapper.writeValueAsString(Map.of("novel", novel));
        for (String output : new String[] {apiJson, backupJson, novel.toString()}) {
            assertFalse(output.contains("test-secret"));
            assertFalse(output.contains("webdavPassword"));
        }
        assertEquals("test-secret", novel.getWebdavPassword());
        assertTrue(apiJson.contains("Test novel"));
    }

    @Test void genericNovelInputCannotSetCredentials() throws Exception {
        Novel novel = new ObjectMapper().readValue(
                "{\"title\":\"Test\",\"webdavPassword\":\"injected\"}", Novel.class);
        assertNull(novel.getWebdavPassword());
    }
}
