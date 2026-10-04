package com.novelwriting.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data @Entity @Table(name = "backup_retention")
public class BackupRetention {
  @Id private Long novelId;
  private int keepLast = 20;
  private int keepDays = 30;
  private long version;
}
