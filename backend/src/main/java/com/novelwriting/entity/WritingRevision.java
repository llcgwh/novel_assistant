package com.novelwriting.entity;
import jakarta.persistence.*;
import lombok.Data;
@Data @Entity @Table(name="writing_revisions",indexes=@Index(columnList="novelId,chapterUid"))
public class WritingRevision {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 private Long novelId;
 @Column(length=36) private String chapterUid;
 private long revision;
 @Column(length=100) private String label;
 @Column(columnDefinition="TEXT",nullable=false) private String snapshot;
 private java.time.LocalDateTime createdAt;
}
