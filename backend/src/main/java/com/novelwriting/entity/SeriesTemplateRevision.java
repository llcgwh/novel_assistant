package com.novelwriting.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "series_template_revisions", indexes = @Index(name = "idx_series_revision_template", columnList = "templateUid"))
public class SeriesTemplateRevision {
  @Id @Column(length = 36) private String uid;
  @Column(nullable = false, length = 36) private String templateUid;
  // JSON is an immutable complete envelope, including its content hash.
  @Column(nullable = false, columnDefinition = "TEXT") private String payload;
}
