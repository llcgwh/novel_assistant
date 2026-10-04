package com.novelwriting.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data @Entity @Table(name = "backup_cleanups")
public class BackupCleanup {
  @Id @Column(length = 36) private String token;
  @Column(nullable = false) private Long novelId;
  @Column(nullable = false) private Instant expiresAt;
  @Column(nullable = false, length = 20) private String status;
  @Column(length = 36) private String requestId;
  @Column(nullable = false, columnDefinition = "TEXT") private String preview;
  @Column(columnDefinition = "TEXT") private String result;
}
