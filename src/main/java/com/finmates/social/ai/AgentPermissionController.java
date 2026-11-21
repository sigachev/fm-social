package com.finmates.social.ai;

import com.finmates.social.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

/**
 * Settings → Connected apps: what AI assistants may do. Only the user changes these, with their own session: the
 * routes are not on the MCP allowlist, so an assistant's token can't read or widen its own permissions.
 */
@RestController
@Tag(name = "AI assistants")
public class AgentPermissionController {

    public record Blocked(Set<String> blocked) {}

    private final AgentPermissionService permissions;
    private final AuthenticatedUser me;

    public AgentPermissionController(AgentPermissionService permissions, AuthenticatedUser me) {
        this.permissions = permissions;
        this.me = me;
    }

    @GetMapping("/api/settings/ai-permissions")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "What AI assistants may do for you (all allowed by default)")
    public Map<String, Boolean> get() {
        return permissions.get(me.currentUserId());
    }

    @PutMapping("/api/settings/ai-permissions")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Switch AI assistant capabilities on or off (send only what changes)")
    public Map<String, Boolean> update(@RequestBody Map<String, Boolean> changes) {
        try {
            return permissions.update(me.currentUserId(), changes);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Service-to-service (fm-mcp, X-Internal-Secret via InternalSecretFilter): what's switched off for a user. */
    @GetMapping("/api/internal/users/{userId}/ai-permissions")
    public Blocked blocked(@PathVariable Long userId) {
        return new Blocked(permissions.blocked(userId));
    }
}
