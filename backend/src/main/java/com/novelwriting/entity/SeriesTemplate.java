package com.novelwriting.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "series_templates")
public class SeriesTemplate {
  @Id @Column(length = 36) private String uid;
  @Column(nullable = false, length = 20) private String kind;
  @Column(nullable = false, length = 36) private String headRevisionUid;
  private long lockVersion;
  private boolean archived;
  @Column(nullable = false, length = 40) private String createdAt;
  @Column(nullable = false, length = 40) private String updatedAt;
}
