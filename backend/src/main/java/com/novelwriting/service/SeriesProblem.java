package com.novelwriting.service;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/** A safe, structured error for the author-owned series setting workflow. */
public class SeriesProblem extends ResponseStatusException {
  private final String code;

  public SeriesProblem(int status, String code, String message) {
    super(HttpStatusCode.valueOf(status), message);
    this.code = code;
  }

  public String getCode() { return code; }
}
