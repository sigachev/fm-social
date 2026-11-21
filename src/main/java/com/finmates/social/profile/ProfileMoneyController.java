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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
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
    private final com.finmates.social.access.AudienceService audience;

    public ProfileMoneyController(ProfileRepository profiles, FollowRepository follows, BlockRepository blocks,
                                  UserLookupCache users, AuthenticatedUser authenticatedUser,
                                  @Qualifier("cryptoServiceWebClient") WebClient crypto,
                                  @Qualifier("uniswapServiceWebClient") WebClient uniswap,
                                  com.finmates.social.access.AudienceService audience) {
        this.audience = audience;
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
        View v = view(username);
        if (v == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(moneyFor(v));
    }

    /**
     * Service-to-service (fm-messaging's chat cards): {@code userId}'s money exactly as {@code viewerId} may see it on
     * their profile, privacy settings applied. null when the viewer can't see the profile at all.
     */
    public MoneyResponse moneyAsSeenBy(Long viewerId, Long userId) {
        View v = viewFor(viewerId, userId);
        return v == null ? null : moneyFor(v);
    }

    private MoneyResponse moneyFor(View v) {
        Long userId = v.userId();
        boolean owner = v.owner();
        Profile p = v.profile();
        Access access = v.access();

        java.util.List<String> hidden = new java.util.ArrayList<>();
        if (!access.portfolio()) hidden.add(p.getPortfolioVisibility() == PortfolioVisibility.MATES ? "portfolio:mates" : "portfolio");
        else {
            if (!access.pnl()) hidden.add("pnl");
            if (!access.positions()) hidden.add("positions");
            if (!access.trades()) hidden.add("trades");
        }

        if (!access.portfolio()) {
            return new MoneyResponse(userId, owner, access, hidden, null, null, null);
        }

        Mono<JsonNode> sumM = get(crypto, "/api/internal/users/{id}/public-summary", userId, "summary");
        Mono<JsonNode> posM = access.positions() ? get(uniswap, "/api/internal/users/{id}/positions", userId, "positions") : Mono.empty();
        Mono<JsonNode> trM = access.trades() ? get(uniswap, "/api/internal/users/{id}/trades?limit=20", userId, "trades") : Mono.empty();

        JsonNode sum = sumM.blockOptional().orElse(null);
        if (sum instanceof ObjectNode o) {
            if (!access.pnl()) {
                o.remove(java.util.List.of("return30dPct", "return12mPct", "profit12mUsd", "profit30dUsd"));
                // The profit line is P&L too: keep the value line, drop pnlUsd from every point.
                for (String key : java.util.List.of("series90d", "series365d")) {
                    if (o.get(key) instanceof com.fasterxml.jackson.databind.node.ArrayNode arr) {
                        arr.forEach(n -> { if (n instanceof ObjectNode pt) pt.remove("pnlUsd"); });
                    }
                }
            }
            if (!access.positions()) {
                o.remove(java.util.List.of("topHoldings", "lpUsd", "walletValues", "mix"));
            }
        }
        return new MoneyResponse(userId, owner, access, hidden, sum,
                posM.blockOptional().orElse(null), trM.blockOptional().orElse(null));
    }

    /** Who is looking at whose profile, and what the owner's privacy settings let them see. */
    private record View(Long userId, boolean owner, Profile profile, Access access) {}

    /** null when the profile must look like it doesn't exist (unknown, inactive, private or blocked). */
    private View view(String username) {
        Long viewerId = null;
        try { viewerId = authenticatedUser.currentUserId(); } catch (Exception ignored) { /* anonymous */ }

        var summary = users.getByUsername(username);
        if (summary.isEmpty() || !summary.get().isActive() || summary.get().userId() == null) {
            return null;
        }
        return viewFor(viewerId, summary.get().userId());
    }

    /** Same as {@link #view(String)} for a known user id; viewerId null = anonymous. */
    private View viewFor(Long viewerId, Long userId) {
        Profile p = profiles.findById(userId).orElse(null);
        if (p == null) return null;

        var viewer = audience.viewer(viewerId);
        boolean owner = viewer.isOwner(userId);
        // Profile hidden from this viewer (only me, mates-only for a non-mate, a private account they don't follow,
        // a block): the money side doesn't exist for them either.
        if (!owner && !viewer.canSeeAccount(userId)) return null;
        boolean portfolio = owner || viewer.canSeePortfolio(userId);
        Access access = new Access(portfolio,
                portfolio && (owner || p.isShowPnl()),
                portfolio && (owner || p.isShowPositions()),
                portfolio && (owner || (p.isShowTradingActivity() && p.isShowTradeHistory())));
        return new View(userId, owner, p, access);
    }

    /**
     * One Uniswap position in depth, for a profile visitor. Needs both positions and returns to be
     * visible (the page is mostly value and PnL), and the position must sit in one of the profile
     * owner's verified wallets (fm-uniswap checks that). Anything else is a 404.
     */
    @GetMapping("/{username}/positions/{chain}/{tokenId}")
    @PreAuthorize("permitAll()")
    @Operation(summary = "One Uniswap position of a profile (verified wallets), if the owner's privacy settings allow it")
    public ResponseEntity<JsonNode> position(@PathVariable String username, @PathVariable String chain,
                                             @PathVariable String tokenId) {
        if (!chain.matches("[a-z0-9-]{1,32}") || !tokenId.matches("\\d{1,78}")) return ResponseEntity.notFound().build();
        View v = view(username);
        if (v == null || !v.access().positions() || !v.access().pnl()) return ResponseEntity.notFound().build();
        JsonNode detail = uniswap.get().uri("/api/internal/users/{id}/positions/{chain}/{tokenId}", v.userId(), chain, tokenId)
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(e -> {
                    log.info("event=profile_position_unavailable user={} chain={} token={} error={}", v.userId(), chain, tokenId, e.toString());
                    return Mono.empty();
                })
                .block();
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }

    // ── mates' money (dashboard) ─────────────────────────────────────────────────

    /** One mate (or the viewer) on the dashboard; every money field is null when that mate hides it. */
    public record MateMoney(Long userId, String username, String displayName, boolean me, Access access,
                            String status, Integer walletCount, java.math.BigDecimal netWorthUsd,
                            java.math.BigDecimal return30dPct, java.math.BigDecimal return12mPct,
                            JsonNode topHolding, JsonNode lastTrade, String asOf) {}

    public record MatesMoneyResponse(MateMoney me, java.util.List<MateMoney> mates, int total, boolean truncated) {}

    private static final int MATES_LIMIT = 100;

    /**
     * The viewer and their mates (mutual active follows), each filtered by that mate's own privacy
     * settings exactly as on their profile: hidden portfolio -> no money at all, hidden P&L -> no
     * returns, hidden positions -> no top holding, hidden trades -> no last trade. Ranking mates by
     * return uses only what each mate already shows the viewer; the public leaderboard opt-in is not
     * needed here. COMPUTING summaries come back with status COMPUTING; poll until they settle.
     */
    @GetMapping("/me/mates-money")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Dashboard: the viewer's and their mates' money, each mate's privacy applied")
    public MatesMoneyResponse matesMoney(@RequestParam(defaultValue = "true") boolean withLastTrade) {
        return matesMoneyFor(authenticatedUser.currentUserId(), withLastTrade);
    }

    /** Same as {@link #matesMoney} for a given viewer (the daily digest). */
    public MatesMoneyResponse matesMoneyFor(Long viewerId, boolean withLastTrade) {
        var page = follows.findActiveFollowingMatesOnly(viewerId, org.springframework.data.domain.PageRequest.of(0, MATES_LIMIT));
        java.util.List<Long> ids = page.getContent().stream().map(f -> f.getFollowedId()).distinct().toList();

        java.util.List<MateMoney> all = Flux.fromIterable(java.util.stream.Stream.concat(java.util.stream.Stream.of(viewerId), ids.stream()).toList())
                .flatMapSequential(id -> Mono.fromCallable(() -> java.util.Optional.ofNullable(viewFor(viewerId, id)))
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                        .flatMap(o -> o.map(v -> mateMoney(v, withLastTrade)).orElse(Mono.empty())), 8)
                .collectList()
                .block(Duration.ofSeconds(20));
        if (all == null) all = java.util.List.of();
        MateMoney me = all.stream().filter(MateMoney::me).findFirst().orElse(null);
        java.util.List<MateMoney> mates = all.stream().filter(m -> !m.me()).toList();
        return new MatesMoneyResponse(me, mates, (int) page.getTotalElements(), page.getTotalElements() > MATES_LIMIT);
    }

    private Mono<MateMoney> mateMoney(View v, boolean withLastTrade) {
        String username = users.getByUserId(v.userId()).map(UserLookupCache.UserSummary::username).orElse(null);
        String name = displayName(v.profile(), username);
        Access a = v.access();
        if (!a.portfolio()) {
            return Mono.just(new MateMoney(v.userId(), username, name, v.owner(), a, "HIDDEN", null, null, null, null, null, null, null));
        }
        Mono<java.util.Optional<JsonNode>> sumM = get(crypto, "/api/internal/users/{id}/public-summary", v.userId(), "summary")
                .map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty());
        Mono<java.util.Optional<JsonNode>> trM = withLastTrade && a.trades()
                ? get(uniswap, "/api/internal/users/{id}/trades?limit=1", v.userId(), "trades").map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
                : Mono.just(java.util.Optional.empty());
        return Mono.zip(sumM, trM).map(t -> {
            JsonNode s = t.getT1().orElse(null);
            JsonNode tr = t.getT2().map(n -> n.path("trades").path(0)).filter(n -> !n.isMissingNode()).orElse(null);
            if (s == null) {
                return new MateMoney(v.userId(), username, name, v.owner(), a, "UNAVAILABLE", null, null, null, null, null, tr, null);
            }
            JsonNode top = a.positions() ? s.path("topHoldings").path(0) : null;
            return new MateMoney(v.userId(), username, name, v.owner(), a,
                    s.path("status").asText("UNAVAILABLE"),
                    s.path("wallets").isArray() ? s.path("wallets").size() : null,
                    dec(s.path("netWorthUsd")),
                    a.pnl() ? dec(s.path("return30dPct")) : null,
                    a.pnl() ? dec(s.path("return12mPct")) : null,
                    top == null || top.isMissingNode() ? null : top,
                    tr,
                    s.path("asOf").isTextual() ? s.path("asOf").asText() : null);
        });
    }

    public record MateTrade(Long userId, String username, String displayName, JsonNode trade) {}
    public record MateBuying(String symbol, int mates, java.math.BigDecimal usd, java.util.List<Long> userIds) {}
    public record MatesActivityResponse(java.util.List<MateTrade> trades, java.util.List<MateBuying> buying, int buyingWindowDays) {}

    /**
     * Dashboard: mates' recent on-chain swaps from their verified wallets (only mates who show their
     * trades to the viewer), newest first, plus what most mates bought in the last 7 days.
     */
    @GetMapping("/me/mates-activity")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Dashboard: mates' recent swaps and what they are buying, each mate's privacy applied")
    public MatesActivityResponse matesActivity(@RequestParam(defaultValue = "20") int limit) {
        Long viewerId = authenticatedUser.currentUserId();
        int max = Math.max(1, Math.min(limit, 50));
        var page = follows.findActiveFollowingMatesOnly(viewerId, org.springframework.data.domain.PageRequest.of(0, MATES_LIMIT));
        java.util.List<Long> ids = page.getContent().stream().map(f -> f.getFollowedId()).distinct().toList();

        java.util.List<MateTrade> trades = Flux.fromIterable(ids)
                .flatMap(id -> Mono.fromCallable(() -> java.util.Optional.ofNullable(viewFor(viewerId, id)))
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                        .flatMapMany(o -> {
                            View v = o.orElse(null);
                            if (v == null || !v.access().trades()) return Flux.empty();
                            String username = users.getByUserId(id).map(UserLookupCache.UserSummary::username).orElse(null);
                            String name = displayName(v.profile(), username);
                            return get(uniswap, "/api/internal/users/{id}/trades?limit=20", id, "trades")
                                    .flatMapMany(n -> Flux.fromIterable(n.path("trades")))
                                    .map(t -> new MateTrade(id, username, name, t));
                        }), 8)
                .collectList()
                .block(Duration.ofSeconds(20));
        if (trades == null) trades = java.util.List.of();

        java.util.List<MateTrade> sorted = new java.util.ArrayList<>(trades);
        sorted.sort(java.util.Comparator.comparing((MateTrade t) -> t.trade().path("timestamp").asText("")).reversed());

        java.time.Instant since = java.time.Instant.now().minus(Duration.ofDays(7));
        java.util.Map<String, java.util.Set<Long>> who = new java.util.HashMap<>();
        java.util.Map<String, java.math.BigDecimal> usd = new java.util.HashMap<>();
        for (MateTrade t : sorted) {
            JsonNode tr = t.trade();
            java.time.Instant at;
            try { at = java.time.Instant.parse(tr.path("timestamp").asText()); } catch (Exception e) { continue; }
            if (at.isBefore(since)) continue;
            String sym = tr.path("bought").path("symbol").asText("").toUpperCase(java.util.Locale.ROOT);
            if (sym.isEmpty() || STABLES.contains(sym)) continue;      // buying a stablecoin is selling something else
            who.computeIfAbsent(sym, k -> new java.util.LinkedHashSet<>()).add(t.userId());
            if (tr.path("amountUsd").isNumber()) usd.merge(sym, tr.path("amountUsd").decimalValue(), java.math.BigDecimal::add);
        }
        java.util.List<MateBuying> buying = who.entrySet().stream()
                .map(e -> new MateBuying(e.getKey(), e.getValue().size(), usd.getOrDefault(e.getKey(), java.math.BigDecimal.ZERO),
                        java.util.List.copyOf(e.getValue())))
                .sorted(java.util.Comparator.comparingInt(MateBuying::mates).reversed()
                        .thenComparing(MateBuying::usd, java.util.Comparator.reverseOrder()))
                .limit(6).toList();
        return new MatesActivityResponse(sorted.stream().limit(max).toList(), buying, 7);
    }

    private static final java.util.Set<String> STABLES = java.util.Set.of(
            "USDC", "USDT", "DAI", "USDC.E", "USDBC", "USDE", "FRAX", "LUSD", "PYUSD", "USDS", "GHO", "TUSD", "FDUSD");

    private static java.math.BigDecimal dec(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() || !n.isNumber() ? null : n.decimalValue();
    }

    private static String displayName(Profile p, String username) {
        if (p.getDisplayName() != null && !p.getDisplayName().isBlank()) return p.getDisplayName().trim();
        String full = ((p.getFirstName() == null ? "" : p.getFirstName()) + " " + (p.getLastName() == null ? "" : p.getLastName())).trim();
        if (p.isShowRealName() && !full.isEmpty()) return full;
        return username;
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
