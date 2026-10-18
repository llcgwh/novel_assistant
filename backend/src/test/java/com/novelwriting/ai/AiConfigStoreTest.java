package com.novelwriting.ai;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiConfigStoreTest {
  static final ObjectMapper JSON = new ObjectMapper();
  @TempDir Path directory;
  AiConfigStore store() { return new AiConfigStore(directory.resolve("private/config.json").toString(), directory.resolve("private/ai.key").toString(), new AiEndpointPolicy()); }
  ObjectNode config(String revision) {
    return JSON.createObjectNode().put("baseUrl", "https://provider.example/v1").put("model", "test-model")
      .put("endpointMode", "remote").put("apiKey", "dummy-secret-not-real").put("expectedRevision", revision);
  }
  @Test void emptyStatusDoesNotCreateKeysAndSecretNeverAppearsInStatusOrPlainConfiguration() throws Exception {
    var store = store();
    assertFalse(store.status().path("configured").asBoolean());
    assertFalse(Files.exists(directory.resolve("private")));
    var status = store.update(config("0"));
    assertTrue(status.path("hasKey").asBoolean());
    assertFalse(status.toString().contains("dummy-secret"));
    assertFalse(Files.readString(directory.resolve("private/config.json")).contains("dummy-secret"));
    assertEquals("dummy-secret-not-real", store().key(store().configuration()));
    assertFalse(Files.exists(directory.resolve("private/webdav.key")));
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("private/config.json")));
      assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("private/ai.key")));
    }
  }
  @Test void changingEndpointCannotReuseOldKeyAndFailurePreservesOriginal() {
    var store = store(); var status = store.update(config("0"));
    ObjectNode changed = config(status.path("revision").asText()).put("baseUrl", "https://other.example/v1"); changed.remove("apiKey");
    assertThrows(AiProblem.class, () -> store.update(changed));
    assertEquals(status, store.status());
    assertEquals("dummy-secret-not-real", store.key(store.configuration()));
    changed.put("apiKey", "new-dummy"); store.update(changed);
    assertEquals("new-dummy", store.key(store.configuration()));
  }
  @Test void equivalentUrlAndModelChangeRetainsKeyButStaleRevisionCannotMutate() {
    var store = store(); var status = store.update(config("0"));
    ObjectNode changed = config(status.path("revision").asText()).put("baseUrl", "https://PROVIDER.example:443/v1/").put("model", "another"); changed.remove("apiKey");
    store.update(changed);
    assertEquals("dummy-secret-not-real", store.key(store.configuration()));
    assertEquals("https://provider.example/v1", store.status().path("baseUrl").asText());
    assertEquals(409, assertThrows(AiProblem.class, () -> store.update(changed)).status());
  }
  @Test void explicitDeletionDisablesRemoteAndLocalModeMayOperateWithoutCredentials() {
    var store = store(); var status = store.update(config("0"));
    ObjectNode clear = config(status.path("revision").asText()).put("clearKey", true); clear.remove("apiKey");
    var cleared = store.update(clear); assertFalse(cleared.path("hasKey").asBoolean()); assertFalse(cleared.path("configured").asBoolean());
    assertThrows(AiProblem.class, store::configuration);
    var local = config(cleared.path("revision").asText()).put("baseUrl", "http://127.0.0.1:11434/v1").put("endpointMode", "local"); local.remove("apiKey");
    assertTrue(store.update(local).path("configured").asBoolean());
    assertEquals("", store.key(store.configuration()));
  }
  @Test void encryptedKeyIsCryptographicallyBoundToOriginalEndpoint() throws Exception {
    var original = store(); original.update(config("0"));
    Path file = directory.resolve("private/config.json");
    ObjectNode persisted = (ObjectNode) JSON.readTree(Files.readAllBytes(file));
    persisted.put("baseUrl", "https://evil.example/v1"); Files.writeString(file, persisted.toString());
    var tampered = store();
    assertEquals("AI_KEY_UNAVAILABLE", assertThrows(AiProblem.class, () -> tampered.key(tampered.configuration())).code());
  }
  @Test void limitsControlsAndMissingEncryptionKeyFailClosed() throws Exception {
    var store = store();
    assertThrows(AiProblem.class, () -> store.update(config("0").put("apiKey", "secret\nInjected")));
    assertThrows(AiProblem.class, () -> store.update(config("0").put("maxOutputTokens", 99999)));
    store.update(config("0")); Files.delete(directory.resolve("private/ai.key"));
    var missing = store();
    assertEquals("AI_KEY_UNAVAILABLE", assertThrows(AiProblem.class, () -> missing.key(missing.configuration())).code());
    assertFalse(Files.exists(directory.resolve("private/ai.key")));
  }
  @Test void tokenParameterIsExplicitPersistedAndBackwardCompatible() throws Exception {
    var store = store(); assertEquals("max_tokens", store.status().path("tokenParameter").asText());
    assertThrows(AiProblem.class, () -> store.update(config("0").put("tokenParameter", "arbitrary")));
    store.update(config("0").put("tokenParameter", "max_completion_tokens"));
    assertEquals("max_completion_tokens", store().configuration().tokenParameter());
    Path file = directory.resolve("private/config.json"); ObjectNode saved = (ObjectNode) JSON.readTree(Files.readAllBytes(file));
    saved.remove("tokenParameter"); Files.writeString(file, saved.toString());
    assertEquals("max_tokens", store().configuration().tokenParameter());
  }
  @Test void proxyIsOptionalPersistedAndSwitchingToDirectRetainsBoundCredential() throws Exception {
    var store = store(); assertEquals("", store.status().path("proxyUrl").asText());
    var first = store.update(config("0"));
    ObjectNode change = config(first.path("revision").asText()).put("proxyUrl", "http://127.0.0.1:7897");
    change.remove("apiKey");
    var proxied = store.update(change);
    assertEquals("http://127.0.0.1:7897", store().configuration().proxyUrl());
    assertEquals("dummy-secret-not-real", store.key(store.configuration()));
    change.put("expectedRevision", proxied.path("revision").asText()).put("proxyUrl", "");
    var direct = store.update(change);
    assertNotEquals(proxied.path("revision"), direct.path("revision"));
    assertEquals("", store().configuration().proxyUrl());
    assertEquals("dummy-secret-not-real", store().key(store().configuration()));
    Path file = directory.resolve("private/config.json");
    ObjectNode legacy = (ObjectNode) JSON.readTree(Files.readAllBytes(file));
    legacy.remove("proxyUrl"); Files.writeString(file, legacy.toString());
    assertEquals("", store().configuration().proxyUrl());
  }
  @Test void proxyCannotBeRemoteCarryCredentialsOrBeUsedForLocalModel() {
    var store = store();
    for (String invalid : new String[]{"http://10.1.2.3:7897", "http://proxy.example:7897", "http://user:secret@127.0.0.1:7897", "socks5://127.0.0.1:7897"})
      assertThrows(AiProblem.class, () -> store.update(config("0").put("proxyUrl", invalid)));
    assertFalse(Files.exists(directory.resolve("private")));
    assertThrows(AiProblem.class, () -> store.update(config("0").put("endpointMode", "local").put("baseUrl", "http://127.0.0.1:11434/v1").put("proxyUrl", "http://127.0.0.1:7897")));
  }
}
