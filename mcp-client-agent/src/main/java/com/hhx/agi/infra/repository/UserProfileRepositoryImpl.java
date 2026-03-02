package com.hhx.agi.infra.repository;

import com.hhx.agi.domain.model.UserProfile;
import com.hhx.agi.domain.repository.UserProfileRepository;
import com.hhx.agi.infra.dao.UserProfileMapper;
import com.hhx.agi.infra.po.UserProfilePO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class UserProfileRepositoryImpl implements UserProfileRepository {

    @Autowired
    private UserProfileMapper userProfileMapper;

    @Override
    public UserProfile findByUserId(String userId) {
        List<UserProfilePO> pos = userProfileMapper.selectByUserId(userId);
        if (pos == null || pos.isEmpty()) {
            return new UserProfile(userId, java.util.Map.of());
        }
        return UserProfile.fromList(userId, pos.stream()
                .map(po -> new UserProfile.UserProfileEntry(
                        userId,
                        po.getProfileKey(),
                        po.getProfileValue(),
                        po.getSourceMessage()))
                .collect(Collectors.toList()));
    }

    @Override
    public List<UserProfile.UserProfileEntry> findEntriesByUserId(String userId) {
        List<UserProfilePO> pos = userProfileMapper.selectByUserId(userId);
        return pos.stream()
                .map(po -> new UserProfile.UserProfileEntry(
                        userId,
                        po.getProfileKey(),
                        po.getProfileValue(),
                        po.getSourceMessage()))
                .collect(Collectors.toList());
    }

    @Override
    public void save(UserProfile.UserProfileEntry entry) {
        UserProfilePO po = new UserProfilePO();
        po.setUserId(entry.getUserId());
        po.setProfileKey(entry.getKey());
        po.setProfileValue(entry.getValue());
        po.setSourceMessage(entry.getSourceMessage());
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        userProfileMapper.upsert(po);
    }

    @Override
    public void saveAll(List<UserProfile.UserProfileEntry> entries) {
        for (UserProfile.UserProfileEntry entry : entries) {
            save(entry);
        }
    }
}