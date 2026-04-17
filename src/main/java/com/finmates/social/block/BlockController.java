package com.finmates.social.block;

import com.finmates.social.block.dto.BlockResponse;
import com.finmates.social.common.PageResponse;
import com.finmates.social.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/blocks")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Blocks", description = "Block list management")
public class BlockController {

    private final BlockService blockService;
    private final AuthenticatedUser authenticatedUser;

    public BlockController(BlockService blockService, AuthenticatedUser authenticatedUser) {
        this.blockService = blockService;
        this.authenticatedUser = authenticatedUser;
    }

    @PostMapping("/{userId}")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Block a user (also removes follow relationships in both directions)")
    @ApiResponse(responseCode = "201", description = "User blocked")
    @ApiResponse(responseCode = "400", description = "Cannot block yourself")
    @ApiResponse(responseCode = "409", description = "Already blocked")
    public BlockResponse block(@PathVariable Long userId) {
        Long blockerId = authenticatedUser.currentUserId();
        return blockService.block(blockerId, userId);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unblock a user")
    @ApiResponse(responseCode = "204", description = "User unblocked (or was not blocked — idempotent)")
    public void unblock(@PathVariable Long userId) {
        Long blockerId = authenticatedUser.currentUserId();
        blockService.unblock(blockerId, userId);
    }

    @GetMapping
    @Operation(summary = "List blocked users (paginated)")
    @ApiResponse(responseCode = "200", description = "Page of blocked users")
    public PageResponse<BlockResponse> getBlocks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = authenticatedUser.currentUserId();
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return blockService.getBlocks(userId, pageable);
    }
}
