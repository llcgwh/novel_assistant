package com.novelwriting.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(
  name = "writing_chapters",
  uniqueConstraints = @UniqueConstraint(columnNames = { "novel_id", "uid" }),
  indexes = @Index(columnList = "novel_id,position")
)
public class WritingChapter {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "novel_id", nullable = false)
  private Long novelId;

  @Column(nullable = false, length = 36)
  private String uid;

  @Column(length = 36)
  private String volumeId;

  private int position;

  @Column(nullable = false, length = 200)
  private String title;

  @Column(columnDefinition = "TEXT")
  private String summary = "";

  @Column(length = 20)
  private String status = "draft";

  @Column(columnDefinition = "TEXT")
  private String notes = "";

  private int goal = 2000;
  private boolean numbered = true;
  private boolean deleted = false;
  private long revision = 0;
  private int wordCount = 0;

  @Column(name = "document_json", columnDefinition = "TEXT", nullable = false)
  private String document;

  @Column(name = "links_json", columnDefinition = "TEXT", nullable = false)
  private String links = "[]";

  @Column(length = 36)
  private String mutationId;

  private java.time.LocalDateTime updatedAt;
}
