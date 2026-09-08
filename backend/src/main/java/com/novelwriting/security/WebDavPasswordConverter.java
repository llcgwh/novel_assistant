package com.novelwriting.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

@Component
@Converter
public class WebDavPasswordConverter implements AttributeConverter<String, String> {
    private final CredentialCipher cipher;
    public WebDavPasswordConverter(CredentialCipher cipher) { this.cipher = cipher; }
    @Override public String convertToDatabaseColumn(String value) { return cipher.encrypt(value); }
    @Override public String convertToEntityAttribute(String value) { return cipher.decrypt(value); }
}
