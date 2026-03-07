package com.hhx.agi.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hhx.agi.infra.config.JwtUtils;
import com.hhx.agi.infra.dao.UserMapper;
import com.hhx.agi.infra.po.UserPO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

@Service
public class AuthService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtils jwtUtils;

    public record LoginResponse(String token, String username, String nickname) {}

    /**
     * 简单密码哈希（生产环境请使用 BCrypt）
     */
    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean verifyPassword(String password, String hash) {
        return hashPassword(password).equals(hash);
    }

    public LoginResponse register(String username, String password, String nickname) {
        // 检查用户名是否已存在
        LambdaQueryWrapper<UserPO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(UserPO::getUsername, username);
        if (userMapper.selectCount(queryWrapper) > 0) {
            throw new RuntimeException("用户名已存在");
        }

        // 创建用户
        UserPO user = new UserPO();
        user.setUsername(username);
        user.setPasswordHash(hashPassword(password));
        user.setNickname(nickname != null ? nickname : username);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);

        // 生成 token
        String token = jwtUtils.generateToken(username);
        return new LoginResponse(token, username, user.getNickname());
    }

    public LoginResponse login(String username, String password) {
        LambdaQueryWrapper<UserPO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(UserPO::getUsername, username);
        UserPO user = userMapper.selectOne(queryWrapper);

        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        if (!verifyPassword(password, user.getPasswordHash())) {
            throw new RuntimeException("密码错误");
        }

        String token = jwtUtils.generateToken(username);
        return new LoginResponse(token, username, user.getNickname());
    }

    public UserPO getUserByUsername(String username) {
        LambdaQueryWrapper<UserPO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(UserPO::getUsername, username);
        return userMapper.selectOne(queryWrapper);
    }

    public UserPO getUserById(Long id) {
        return userMapper.selectById(id);
    }

    /**
     * 修改密码
     */
    public void changePassword(String username, String oldPassword, String newPassword) {
        LambdaQueryWrapper<UserPO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(UserPO::getUsername, username);
        UserPO user = userMapper.selectOne(queryWrapper);

        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        if (!verifyPassword(oldPassword, user.getPasswordHash())) {
            throw new RuntimeException("旧密码错误");
        }

        user.setPasswordHash(hashPassword(newPassword));
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }
}