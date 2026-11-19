package com.finmates.social.profile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service (X-Internal-Secret): a profile's money as another user may see it. fm-messaging uses it for the
 * "me vs a mate" card, so a card never shows more of the mate than the mate shows the sender on their profile.
 */
@RestController
@RequestMapping("/api/internal/profiles")
@Tag(name = "Internal: profiles")
public class ProfileMoneyInternalController {

    private final ProfileMoneyController money;

    public ProfileMoneyInternalController(ProfileMoneyController money) {
        this.money = money;
    }

    @GetMapping("/{userId}/money")
    @Operation(summary = "A profile's money as viewerId sees it (privacy settings applied); 404 when hidden from them")
    public ResponseEntity<ProfileMoneyController.MoneyResponse> money(@PathVariable Long userId, @RequestParam Long viewer) {
        ProfileMoneyController.MoneyResponse m = money.moneyAsSeenBy(viewer, userId);
        return m == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(m);
    }
}
