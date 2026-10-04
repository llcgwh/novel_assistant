package com.novelwriting.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "series_catalog_state")
public class SeriesCatalogState {
  @Id private Long id;
  @Column(nullable = false) private long version;
}
