package com.novelwriting.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Fixed test-only key: production must use its own randomly generated key. */
@TestConfiguration
@Import(WebDavPasswordConverter.class)
public class TestCredentials {
    @Bean CredentialCipher credentialCipher() {
        return new CredentialCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "unused-test-key");
    }
}
