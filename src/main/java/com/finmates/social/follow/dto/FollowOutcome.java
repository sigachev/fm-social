package com.finmates.social.follow.dto;

/**
 * Result of {@code FollowService.follow}, used to drive the controller's HTTP status
 * (201 Created on the insert path, 200 OK on the idempotent re-follow path) without
 * exposing a side-channel to API consumers — both responses carry the same
 * {@link FollowResponse} body shape.
 *
 * @param created  {@code true} when a new {@code follows} row was inserted; {@code false}
 *                 when an existing row was returned unchanged.
 * @param response the canonical {@link FollowResponse} for the resulting relationship.
 */
public record FollowOutcome(boolean created, FollowResponse response) {}
