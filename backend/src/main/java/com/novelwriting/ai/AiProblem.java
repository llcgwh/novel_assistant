package com.novelwriting.ai;

/** Only application-owned messages may cross the AI HTTP boundary. */
public final class AiProblem extends RuntimeException {
  private final int status;
  private final String code;
  // Request-local repair data only: never included in messages, logs or HTTP errors.
  private String correctionContent;
  public AiProblem(int status, String code, String message) { super(message); this.status = status; this.code = code; }
  public int status() { return status; }
  public String code() { return code; }
  AiProblem correctionContent(String content) { this.correctionContent = content; return this; }
  String correctionContent() { return correctionContent; }
  public static AiProblem bad(String message) { return new AiProblem(400, "AI_INVALID_REQUEST", message); }
}
