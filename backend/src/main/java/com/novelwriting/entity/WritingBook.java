package com.novelwriting.entity;
import jakarta.persistence.*;
import lombok.Data;
@Data @Entity @Table(name="writing_books")
public class WritingBook {
 @Id private Long novelId;
 @Column(nullable=false,length=36) private String uid;
 private long structureVersion;
 @Column(columnDefinition="TEXT",nullable=false) private String volumes="[]";
 @Column(columnDefinition="TEXT",nullable=false) private String preferences="{}";
 private long changeSequence;
 private long syncedSequence;
 @Column(length=100) private String remoteBase;
 @Column(columnDefinition="TEXT") private String syncMessage;
 private java.time.LocalDateTime lastSync;
}
