package com.auditiq.auth;

import com.auditiq.auth.AuthDtos.AuthResponse;
import com.auditiq.auth.AuthDtos.LoginRequest;
import com.auditiq.auth.AuthDtos.RefreshRequest;
import com.auditiq.auth.AuthDtos.RegisterRequest;
import com.auditiq.auth.AuthDtos.UserSummary;
import com.auditiq.common.Exceptions.NotFoundException;
import com.auditiq.security.CurrentUser;
import com.auditiq.user.UserRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final UserRepository users;

    public AuthController(AuthService authService, UserRepository users) {
        this.authService = authService;
        this.users = users;
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate and obtain an access + refresh token pair")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new token pair (the old refresh token is revoked)")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a refresh token")
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Self-register as an Auditor (only when AUDITIQ_SELF_REGISTRATION=true)")
    public UserSummary register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @GetMapping("/me")
    @Operation(summary = "Profile of the authenticated user")
    public UserSummary me() {
        Long id = CurrentUser.require().id();
        return users.findById(id).map(AuthService::toSummary)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }
}
