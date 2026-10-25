package ru.library.event.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class UserAccount {
    @Id
    @Column(length = 64, nullable = false, updatable = false)
    private String id;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private UserRole role;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public UserAccount() {
        this.createdAt = LocalDateTime.now();
    }

    public UserAccount(String id, String passwordHash) {
        this(id, passwordHash, id);
    }

    public UserAccount(String id, String passwordHash, String displayName) {
        this();
        this.id = id;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = UserRole.USER;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getDisplayName() {
        return displayName == null || displayName.isBlank() ? id : displayName;
    }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public UserRole getRole() {
        return role == null ? UserRole.USER : role;
    }
    public void setRole(UserRole role) { this.role = role; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
