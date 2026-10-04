package com.novelwriting.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "series_mutation_receipts")
public class SeriesMutationReceipt {
  // SHA-256(scope + mutation UUID). Epoch makes pre-restore requests unusable.
  @Id @Column(length = 64) private String id;
  @Column(nullable = false, length = 120) private String scope;
  @Column(nullable = false, length = 36) private String mutationId;
  @Column(nullable = false, length = 64) private String requestHash;
  @Column(nullable = false, columnDefinition = "TEXT") private String result;
}
