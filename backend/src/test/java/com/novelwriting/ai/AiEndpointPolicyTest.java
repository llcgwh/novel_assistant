package com.novelwriting.ai;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import org.junit.jupiter.api.Test;

class AiEndpointPolicyTest {
  AiEndpointPolicy policy = new AiEndpointPolicy();
  @Test void urlSyntaxCannotCarryCredentialsOrChangeRequestPath() {
    for (String invalid : new String[]{"http://provider.example/v1", "https://user:password@provider.example/v1", "https://provider.example/v1?key=secret", "https://provider.example/v1#fragment", "https://provider.example/v1/../admin", "https://provider.example/%2e%2e/admin", "https://provider.example/v1/chat/completions", "ftp://provider.example/v1"}) {
      assertThrows(AiProblem.class, () -> policy.normalize(invalid, "remote"), invalid);
    }
    assertEquals("https://provider.example/v1", policy.normalize("https://PROVIDER.example:443/v1/", "remote"));
    assertThrows(AiProblem.class, () -> policy.normalize("http://192.168.1.3:11434/v1", "local"));
    assertEquals("http://[::1]:11434/v1", policy.normalize("http://[::1]:11434/v1", "local"));
  }
  @Test void privateReservedAndTransitionRangesAreNotRemoteDestinations() throws Exception {
    for (String address : new String[]{"0.0.0.0", "127.0.0.1", "10.1.2.3", "172.31.9.1", "192.168.2.3", "169.254.169.254", "100.64.2.3", "198.18.0.1", "224.0.0.1", "192.0.2.1", "::1", "::", "fc00::1", "fe80::1", "2001:db8::1", "2002:7f00:1::1", "64:ff9b::7f00:1", "::ffff:127.0.0.1"}) {
      assertFalse(AiEndpointPolicy.isPublic(InetAddress.getByName(address)), address);
    }
    assertTrue(AiEndpointPolicy.isPublic(InetAddress.getByAddress(new byte[]{8,8,8,8})));
    assertTrue(AiEndpointPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
  }
  @Test void everyDnsAnswerMustMatchModeAndReturnedAnswersArePinnedCopies() throws Exception {
    InetAddress publicAddress = InetAddress.getByAddress(new byte[]{8,8,8,8});
    AiEndpointPolicy mixed = new AiEndpointPolicy() {
      @Override protected InetAddress[] lookup(String host) throws UnknownHostException {
        return new InetAddress[]{publicAddress, InetAddress.getByAddress(new byte[]{127,0,0,1})};
      }
    };
    assertThrows(AiProblem.class, () -> mixed.resolve("https://provider.example/v1", "remote"));
    AiEndpointPolicy safe = new AiEndpointPolicy() {
      @Override protected InetAddress[] lookup(String host) { return new InetAddress[]{publicAddress}; }
    };
    var target = safe.resolve("https://provider.example/v1", "remote");
    target.addresses()[0] = InetAddress.getByAddress(new byte[]{127,0,0,1});
    assertEquals(publicAddress, target.addresses()[0]);
    assertThrows(AiProblem.class, () -> safe.resolve("http://localhost:11434/v1", "local"));
  }
  @Test void proxyResolutionStillRejectsEveryPrivateAnswerAndDoesNotUseSystemDns() throws Exception {
    InetAddress publicAddress = InetAddress.getByAddress(new byte[]{8,8,8,8});
    AiEndpointPolicy proxy = new AiEndpointPolicy() {
      @Override protected InetAddress[] lookup(String host) { fail("System DNS must not run in proxy mode"); return null; }
      @Override protected InetAddress[] lookupViaProxy(String host, String proxyUrl) {
        assertEquals("provider.example", host); assertEquals("http://127.0.0.1:7897", proxyUrl);
        return new InetAddress[]{publicAddress};
      }
    };
    var target = proxy.resolve("https://provider.example/v1", "remote", "http://127.0.0.1:7897");
    assertEquals("http://127.0.0.1:7897", target.proxyUrl());
    assertEquals("provider.example", target.uri().getHost());
    target.addresses()[0] = InetAddress.getLoopbackAddress();
    assertEquals(publicAddress, target.addresses()[0]);
    AiEndpointPolicy mixed = new AiEndpointPolicy() {
      @Override protected InetAddress[] lookupViaProxy(String host, String proxyUrl) { return new InetAddress[]{publicAddress, InetAddress.getLoopbackAddress()}; }
    };
    assertThrows(AiProblem.class, () -> mixed.resolve("https://provider.example", "remote", "http://127.0.0.1:7897"));
    assertThrows(AiProblem.class, () -> proxy.resolve("http://localhost:11434/v1", "local", "http://127.0.0.1:7897"));
    assertThrows(AiProblem.class, () -> proxy.resolve("https://provider.example", "remote", "http://192.168.1.1:7897"));
  }
  @Test void emptyProxyKeepsExistingDirectResolver() throws Exception {
    AiEndpointPolicy direct = new AiEndpointPolicy() {
      @Override protected InetAddress[] lookup(String host) { return new InetAddress[]{InetAddress.getLoopbackAddress()}; }
      @Override protected InetAddress[] lookupViaProxy(String host, String proxyUrl) { fail("Direct mode must not use proxy DNS"); return null; }
    };
    assertEquals("", direct.resolve("http://localhost:11434/v1", "local", "").proxyUrl());
  }

  @Test void vpnSyntheticDnsUsesVerifiedPublicResolutionWithoutChangingRecipientOrProxy() throws Exception {
    InetAddress publicAddress = InetAddress.getByName("8.8.4.4");
    for (String synthetic : new String[]{"198.18.0.15", "198.19.255.254"}) {
      AiEndpointPolicy vpn = new AiEndpointPolicy() {
        @Override protected InetAddress[] lookup(String host) throws UnknownHostException { return new InetAddress[]{InetAddress.getByName(synthetic)}; }
        @Override protected InetAddress[] lookupPublicDns(String host) { assertEquals("provider.example", host); return new InetAddress[]{publicAddress}; }
        @Override protected InetAddress[] lookupViaProxy(String host, String proxyUrl) { fail("Chosen direct mode must remain direct"); return null; }
      };
      var target = vpn.resolve("https://provider.example/v1", "remote", "");
      assertEquals("https://provider.example/v1/chat/completions", target.uri().toString());
      assertEquals("", target.proxyUrl()); assertArrayEquals(new InetAddress[]{publicAddress}, target.addresses());
    }
  }

  @Test void vpnRecoveryStillRejectsPrivateMixedAndSyntheticPublicDnsAnswers() throws Exception {
    for (String unsafe : new String[]{"127.0.0.1", "10.0.0.1", "198.18.0.15", "169.254.169.254"}) {
      AiEndpointPolicy vpn = new AiEndpointPolicy() {
        @Override protected InetAddress[] lookup(String host) throws UnknownHostException { return new InetAddress[]{InetAddress.getByName("198.18.0.15")}; }
        @Override protected InetAddress[] lookupPublicDns(String host) {
          try { return new InetAddress[]{InetAddress.getByName("8.8.4.4"), InetAddress.getByName(unsafe)}; }
          catch (UnknownHostException impossible) { throw new AssertionError(impossible); }
        }
      };
      assertThrows(AiProblem.class, () -> vpn.resolve("https://provider.example", "remote"), unsafe);
    }
  }

  @Test void normalDnsAndPrivateDestinationsNeverTriggerPublicDnsRecovery() throws Exception {
    for (String answer : new String[]{"8.8.4.4", "127.0.0.1", "10.0.0.1"}) {
      AiEndpointPolicy direct = new AiEndpointPolicy() {
        @Override protected InetAddress[] lookup(String host) throws UnknownHostException { return new InetAddress[]{InetAddress.getByName(answer)}; }
        @Override protected InetAddress[] lookupPublicDns(String host) { fail("Only VPN synthetic answers trigger recovery"); return null; }
      };
      if (answer.equals("8.8.4.4")) assertEquals(answer, direct.resolve("https://provider.example", "remote").addresses()[0].getHostAddress());
      else assertThrows(AiProblem.class, () -> direct.resolve("https://provider.example", "remote"));
    }
    AiEndpointPolicy mixed = new AiEndpointPolicy() {
      @Override protected InetAddress[] lookup(String host) throws UnknownHostException {
        return new InetAddress[]{InetAddress.getByName("198.18.0.15"), InetAddress.getLoopbackAddress()};
      }
      @Override protected InetAddress[] lookupPublicDns(String host) { fail("A private answer must not be bypassed"); return null; }
    };
    assertThrows(AiProblem.class, () -> mixed.resolve("https://provider.example", "remote"));
    assertThrows(AiProblem.class, () -> mixed.resolve("http://localhost:11434", "local"));
  }

  @Test void failedVpnRecoveryNeverUsesTheSyntheticAddress() {
    AiEndpointPolicy vpn = new AiEndpointPolicy() {
      @Override protected InetAddress[] lookup(String host) throws UnknownHostException { return new InetAddress[]{InetAddress.getByName("198.18.0.15")}; }
      @Override protected InetAddress[] lookupPublicDns(String host) { throw new AiProblem(502, "AI_PUBLIC_DNS_FAILED", "VPN address recovery failed"); }
    };
    assertEquals("AI_PUBLIC_DNS_FAILED", assertThrows(AiProblem.class, () -> vpn.resolve("https://provider.example", "remote")).code());
  }
}
