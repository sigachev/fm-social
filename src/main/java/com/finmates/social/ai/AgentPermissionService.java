package com.finmates.social.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What AI assistants may do for a user, one switch per capability. Capabilities are the scopes a user approves when
 * connecting an assistant (fm-mcp's McpToolScopes), minus public market data. Everything is allowed by default.
 */
@Service
public class AgentPermissionService {

    /** In the order the app shows them. */
    public static final List<String> CAPABILITIES = List.of(
            "portfolio:read", "wallets:read", "positions:read", "social:read", "messages:read",
            "messages:write", "alerts:write", "signals:write", "social:write");

    private final JdbcTemplate jdbc;

    public AgentPermissionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every capability with its switch (true unless the user turned it off). */
    public Map<String, Boolean> get(long userId) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String c : CAPABILITIES) out.put(c, true);
        jdbc.query("SELECT capability, allowed FROM ai_agent_permissions WHERE user_id = ?", rs -> {
            if (out.containsKey(rs.getString(1))) out.put(rs.getString(1), rs.getBoolean(2));
        }, userId);
        return out;
    }

    public Set<String> blocked(long userId) {
        Set<String> out = new TreeSet<>();
        get(userId).forEach((c, on) -> { if (!on) out.add(c); });
        return out;
    }

    @Transactional
    public Map<String, Boolean> update(long userId, Map<String, Boolean> changes) {
        for (var e : changes.entrySet()) {
            if (!CAPABILITIES.contains(e.getKey()) || e.getValue() == null) {
                throw new IllegalArgumentException("Unknown capability " + e.getKey());
            }
            jdbc.update("""
                    INSERT INTO ai_agent_permissions (user_id, capability, allowed) VALUES (?, ?, ?)
                    ON CONFLICT (user_id, capability) DO UPDATE SET allowed = EXCLUDED.allowed, updated_at = NOW()""",
                    userId, e.getKey(), e.getValue());
        }
        return get(userId);
    }
}
