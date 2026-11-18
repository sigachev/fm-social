package com.finmates.social.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finmates.social.block.BlockRepository;
import com.finmates.social.client.UserLookupCache;
import com.finmates.social.common.security.AuthenticatedUser;
import com.finmates.social.follow.FollowRepository;
import com.finmates.social.follow.FollowStatus;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Money side of a public profile: net worth, returns, value series and holdings (finmates-crypto),
 * open Uniswap positions and recent swaps (fm-uniswap) — from the user's VERIFIED wallets only.
 *
 * <p>This is where privacy is enforced; the services behind it return everything. Rules, owner
 * excepted: blocked either way or a private profile -> 404; portfolio visibility PRIVATE -> no
 * money data, FOLLOWERS -> only active followers; showPnl, showPositions and
 * showTradingActivity/showTradeHistory each remove their part. Removed parts are absent from the
 * response (not nulled-out values) and listed in {@code hidden}.
 */
@Slf4j
@RestController
@RequestMapping("/api/profiles")
public class ProfileMoneyController {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final ProfileRepository profiles;
    private final FollowRepository follows;
    private final BlockRepository blocks;
    private final UserLookupCache users;
    private final AuthenticatedUser authenticatedUser;
    private final WebClient crypto;
    private final WebClient uniswap;

    public ProfileMoneyController(ProfileRepository profiles, FollowRepository follows, BlockRepository blocks,
                                  UserLookupCache users, AuthenticatedUser authenticatedUser,
                                  @Qualifier("cryptoServiceWebClient") WebClient crypto,
                                  @Qualifier("uniswapServiceWebClient") WebClient uniswap) {
        this.profiles = profiles;
        this.follows = follows;
        this.blocks = blocks;
        this.users = users;
        this.authenticatedUser = authenticatedUser;
        this.crypto = crypto;
        this.uniswap = uniswap;
    }

    public record Access(boolean portfolio, boolean pnl, boolean positions, boolean trades) {}

    public record MoneyResponse(Long userId, boolean owner, Access access, java.util.List<String> hidden,
                                JsonNode summary, JsonNode positions, JsonNode trades) {}

    @GetMapping("/{username}/money")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Public money summary of a profile (verified wallets), filtered by the owner's privacy settings")
    public ResponseEntity<MoneyResponse> money(@PathVariable String username) {
        Long viewerId = null;
        try { viewerId = authenticatedUser.currentUserId(); } catch (Exception ignored) { /* anonymous */ }

        var summary = users.getByUsername(username);
        if (summary.isEmpty() || !summary.get().isActive() || summary.get().userId() == null) {
            return ResponseEntity.notFound().build();
        }
        Long userId = summary.get().userId();
        Profile p = profiles.findById(userId).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();

        boolean owner = userId.equals(viewerId);
        if (!owner) {
            if (p.getProfileVisibility() == ProfileVisibility.PRIVATE) return ResponseEntity.notFound().build();
            if (viewerId != null && blocks.existsBlockInEitherDirection(viewerId, userId)) return ResponseEntity.notFound().build();
        }

        boolean follower = viewerId != null
                && follows.existsByFollowerIdAndFollowedIdAndStatus(viewerId, userId, FollowStatus.ACTIVE);
        boolean portfolio = owner || switch (p.getPortfolioVisibility()) {
            case PUBLIC -> true;
            case FOLLOWERS -> follower;
            case PRIVATE -> false;
        };
        Access access = new Access(portfolio,
                portfolio && (owner || p.isShowPnl()),
                portfolio && (owner || p.isShowPositions()),
                portfolio && (owner || (p.isShowTradingActivity() && p.isShowTradeHistory())));

        java.util.List<String> hidden = new java.util.ArrayList<>();
        if (!access.portfolio()) hidden.add(p.getPortfolioVisibility() == PortfolioVisibility.FOLLOWERS ? "portfolio:followers" : "portfolio");
        else {
            if (!access.pnl()) hidden.add("pnl");
            if (!access.positions()) hidden.add("positions");
            if (!access.trades()) hidden.add("trades");
        }

        if (!access.portfolio()) {
            return ResponseEntity.ok(new MoneyResponse(userId, owner, access, hidden, null, null, null));
        }

        Mono<JsonNode> sumM = get(crypto, "/api/internal/users/{id}/public-summary", userId, "summary");
        Mono<JsonNode> posM = access.positions() ? get(uniswap, "/api/internal/users/{id}/positions", userId, "positions") : Mono.empty();
        Mono<JsonNode> trM = access.trades() ? get(uniswap, "/api/internal/users/{id}/trades?limit=20", userId, "trades") : Mono.empty();

        JsonNode sum = sumM.blockOptional().orElse(null);
        if (sum instanceof ObjectNode o) {
            if (!access.pnl()) {
                o.remove(java.util.List.of("return30dPct", "return12mPct", "profit12mUsd"));
            }
            if (!access.positions()) {
                o.remove(java.util.List.of("topHoldings", "lpUsd"));
            }
        }
        return ResponseEntity.ok(new MoneyResponse(userId, owner, access, hidden, sum,
                posM.blockOptional().orElse(null), trM.blockOptional().orElse(null)));
    }

    private Mono<JsonNode> get(WebClient client, String uri, Long userId, String what) {
        return client.get().uri(uri, userId).retrieve().bodyToMono(JsonNode.class)
                .timeout(TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("event=profile_money_part_failed part={} user={} error={}", what, userId, e.toString());
                    return Mono.empty();
                })
                .cache();
    }
}
