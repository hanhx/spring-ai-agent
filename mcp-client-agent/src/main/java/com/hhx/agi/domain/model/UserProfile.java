package com.hhx.agi.domain.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
public class UserProfile {
    private final String userId;
    private final Map<String, String> preferences;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public UserProfile(String userId, Map<String, String> preferences) {
        this.userId = userId;
        this.preferences = preferences;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public static UserProfile fromList(String userId, List<UserProfileEntry> entries) {
        Map<String, String> prefs = entries.stream()
                .collect(Collectors.toMap(
                        UserProfileEntry::getKey,
                        UserProfileEntry::getValue,
                        (v1, v2) -> v2
                ));
        return new UserProfile(userId, prefs);
    }

    public String toPromptContext() {
        if (preferences == null || preferences.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## 用户偏好\n");
        preferences.forEach((key, value) -> sb.append("- ").append(key).append(": ").append(value).append("\n"));
        return sb.toString();
    }

    @Data
    public static class UserProfileEntry {
        private String userId;
        private String key;
        private String value;
        private String sourceMessage;

        public UserProfileEntry(String userId, String key, String value, String sourceMessage) {
            this.userId = userId;
            this.key = key;
            this.value = value;
            this.sourceMessage = sourceMessage;
        }
    }
}