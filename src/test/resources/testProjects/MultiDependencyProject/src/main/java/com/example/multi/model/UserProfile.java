package com.example.multi.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Simple value object representing a user profile.
 */
public class UserProfile {

    @JsonProperty("user_id")
    private final String userId;

    @JsonProperty("display_name")
    private final String displayName;

    @JsonProperty("email")
    private final String email;

    public UserProfile(String userId, String displayName, String email) {
        this.userId = userId;
        this.displayName = displayName;
        this.email = email;
    }

    public String getUserId()      { return userId; }
    public String getDisplayName() { return displayName; }
    public String getEmail()       { return email; }

    @Override
    public String toString() {
        return "UserProfile{userId='" + userId + "', displayName='" + displayName + "'}";
    }
}
