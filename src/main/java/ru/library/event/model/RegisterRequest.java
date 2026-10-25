package ru.library.event.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class RegisterRequest {
    @NotBlank
    @Size(min = 3, max = 64)
    @Pattern(regexp = "[A-Za-z0-9_-]+")
    private String userId;

    @NotBlank
    @Size(min = 2, max = 100)
    private String displayName;

    @NotBlank
    @Size(min = 6, max = 72)
    private String password;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
