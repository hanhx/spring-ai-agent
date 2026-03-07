package com.hhx.agi.facade.rest;

import com.hhx.agi.application.service.AuthService;
import com.hhx.agi.facade.request.LoginRequest;
import com.hhx.agi.facade.request.PasswordRequest;
import com.hhx.agi.facade.request.RegisterRequest;
import com.hhx.agi.infra.po.UserPO;
import jakarta.validation.Valid;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody @Valid RegisterRequest request) {
        AuthService.LoginResponse response = authService.register(
                request.getUsername(),
                request.getPassword(),
                request.getNickname()
        );
        return ResponseEntity.ok(new AuthResponse(
                response.token(),
                response.username(),
                response.nickname()
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody @Valid LoginRequest request) {
        AuthService.LoginResponse response = authService.login(
                request.getUsername(),
                request.getPassword()
        );
        return ResponseEntity.ok(new AuthResponse(
                response.token(),
                response.username(),
                response.nickname()
        ));
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(
            @RequestHeader("X-Username") String username,
            @RequestBody @Valid PasswordRequest request) {
        authService.changePassword(username, request.getOldPassword(), request.getNewPassword());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserInfo> getCurrentUser(@RequestHeader("X-Username") String username) {
        UserPO user = authService.getUserByUsername(username);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new UserInfo(user.getUsername(), user.getNickname()));
    }

    @Data
    public static class UserInfo {
        private String username;
        private String nickname;

        public UserInfo(String username, String nickname) {
            this.username = username;
            this.nickname = nickname;
        }
    }
}