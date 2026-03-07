package com.hhx.agi.application.service;

import com.hhx.agi.domain.model.UserProfile;
import com.hhx.agi.domain.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private static final String DEFAULT_USER_ID = "anonymous";

    @Autowired
    private UserProfileRepository userProfileRepository;

    /**
     * 获取用户画像上下文（用于拼接到 prompt）
     */
    public String getUserProfileContext(String userId) {
        if (userId == null || userId.isBlank()) {
            userId = DEFAULT_USER_ID;
        }
        try {
            UserProfile profile = userProfileRepository.findByUserId(userId);
            String context = profile.toPromptContext();
            log.info("[UserProfile] userId={}, 加载 {} 个偏好", userId,
                    profile.getPreferences() != null ? profile.getPreferences().size() : 0);
            return context;
        } catch (Exception e) {
            log.warn("[UserProfile] 加载用户画像失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 获取用户 ID（从请求头或默认值）
     */
    public String resolveUserId(String userIdHeader) {
        if (userIdHeader != null && !userIdHeader.isBlank()) {
            return userIdHeader;
        }
        return DEFAULT_USER_ID;
    }

    /**
     * 保存用户画像（供 REST API 调用）
     */
    public void saveProfile(String userId, String profileKey, String profileValue, String sourceMessage) {
        try {
            UserProfile.UserProfileEntry entry = new UserProfile.UserProfileEntry(
                    userId, profileKey, profileValue, sourceMessage);
            userProfileRepository.save(entry);
            log.info("[UserProfile] 保存成功: userId={}, key={}, value={}", userId, profileKey, profileValue);
        } catch (Exception e) {
            log.error("[UserProfile] 保存失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 异步提取并保存用户偏好（对话结束后调用）
     */
    @Async
    public void extractAndSaveProfile(String userId, String userMessage, String assistantResponse) {
        if (userId == null || userId.equals(DEFAULT_USER_ID)) {
            return;
        }
        // TODO: 这里后续可以集成 LLM 来自动提取用户偏好
        // 目前先支持手动保存，通过 REST API
    }
}