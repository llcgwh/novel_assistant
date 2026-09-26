package com.novelwriting.entity;
import jakarta.persistence.*;
import lombok.Data;
@Data @Entity @Table(name="writing_sessions",uniqueConstraints=@UniqueConstraint(columnNames={"novel_id","uid"}))
public class WritingSession {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="novel_id",nullable=false) private Long novelId;
 @Column(length=36,nullable=false) private String uid;
 private long sequence;
 @Column(columnDefinition="TEXT",nullable=false) private String payload;
}
