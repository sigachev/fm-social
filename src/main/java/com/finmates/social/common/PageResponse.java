package com.finmates.social.common;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast()
        );
    }

    /**
     * Build a {@code PageResponse} from already-paginated content + total
     * count — used when the underlying query is native SQL (so there's no
     * {@link Page} to feed {@link #from(Page)}). Avoids constructing a
     * synthetic {@code Page} just to satisfy the existing factory.
     *
     * @param content       the page slice; caller is responsible for slicing
     * @param page          0-indexed page number actually returned
     * @param size          page size that was applied (after clamping)
     * @param totalElements total matching rows across all pages
     */
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = (size <= 0)
                ? 0
                : (int) ((totalElements + size - 1) / size);  // ceil(total/size)
        boolean last = page >= totalPages - 1;
        return new PageResponse<>(content, page, size, totalElements, totalPages, last);
    }
}
