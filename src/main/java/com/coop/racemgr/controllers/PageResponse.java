package com.coop.racemgr.controllers;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable JSON shape for paged results. Serializing Spring Data's PageImpl directly has no
 * guarantee of staying the same between versions. Same field names the API has always returned,
 * minus the internal "pageable" and "sort" objects.
 */
public record PageResponse<T>(List<T> content, int number, int size, int numberOfElements,
                              long totalElements, int totalPages, boolean first, boolean last, boolean empty) {
    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getNumberOfElements(),
                page.getTotalElements(), page.getTotalPages(), page.isFirst(), page.isLast(), page.isEmpty());
    }
}
