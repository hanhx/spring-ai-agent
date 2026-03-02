package com.hhx.agi.domain.repository;

import com.hhx.agi.domain.model.UserProfile;

import java.util.List;

public interface UserProfileRepository {

    UserProfile findByUserId(String userId);

    List<UserProfile.UserProfileEntry> findEntriesByUserId(String userId);

    void save(UserProfile.UserProfileEntry entry);

    void saveAll(List<UserProfile.UserProfileEntry> entries);
}