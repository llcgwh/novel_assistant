package com.novelwriting.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

/** Device-local audit records intentionally have no cascading novel foreign key. */
@Data @Entity
@Table(name = "backup_operations", indexes = @Index(name = "backup_operations_novel_id_id", columnList = "novelId,id"))
public class BackupOperation {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(nullable = false) private Long novelId;
  @Column(nullable = false, length = 40) private String type;
  @Column(nullable = false, length = 20) private String status;
  @Column(nullable = false) private Instant startedAt;
  private Instant finishedAt;
  @Column(length = 60) private String stage;
  @Column(columnDefinition = "TEXT") private String message;
  @Column(length = 160) private String errorType;
  private Integer httpStatus;
  @Column(columnDefinition = "TEXT") private String details;
}
