package com.novelwriting.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "novels")
public class Novel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 100)
    private String author;

    @Column(length = 100)
    private String genre;

    @Column(length = 20)
    private String status = "writing";

    @Column(name = "cover_image", length = 500)
    private String coverImage;

    // WebDAV sync configuration
    @Column(name = "webdav_server_url", length = 500)
    private String webdavServerUrl;

    @Column(name = "webdav_username", length = 100)
    private String webdavUsername;

    @Column(name = "webdav_password", length = 500)
    private String webdavPassword;

    @Column(name = "webdav_auto_sync")
    private Boolean webdavAutoSync = false;

    @Column(name = "last_webdav_sync")
    private LocalDateTime lastWebdavSync;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
