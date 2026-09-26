package com.novelwriting.entity;

import jakarta.persistence.*;
import lombok.Data;

/** Retains the created record identity when a response is lost and the editor retries. */
@Data
@Entity
@Table(name = "form_submissions")
public class FormSubmission {
    @Id @Column(length = 36) private String token;
    @Column(nullable = false) private Long novelId;
    @Column(nullable = false, length = 40) private String resource;
    @Column(nullable = false) private Long recordId;
}
