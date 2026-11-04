package com.auditiq.admin;

import com.auditiq.common.PageResponse;
import com.auditiq.user.UserDtos.CreateUserRequest;
import com.auditiq.user.UserDtos.UpdateRolesRequest;
import com.auditiq.user.UserDtos.UpdateStatusRequest;
import com.auditiq.user.UserDtos.UserResponse;
import com.auditiq.user.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Administration – users")
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "List users")
    public PageResponse<UserResponse> list(@RequestParam(required = false) String search,
                                           @RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return userService.list(search, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a user with one or more roles")
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.adminCreate(request);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Enable or disable a user")
    public UserResponse setStatus(@PathVariable Long id, @Valid @RequestBody UpdateStatusRequest request) {
        return userService.setEnabled(id, request.enabled());
    }

    @PutMapping("/{id}/roles")
    @Operation(summary = "Replace a user's roles")
    public UserResponse setRoles(@PathVariable Long id, @Valid @RequestBody UpdateRolesRequest request) {
        return userService.updateRoles(id, request.roles());
    }

    @PostMapping("/{id}/unlock")
    @Operation(summary = "Clear a lockout caused by failed logins")
    public UserResponse unlock(@PathVariable Long id) {
        return userService.unlock(id);
    }
}
