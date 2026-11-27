package com.fsp.plugin.inventory.service;

import com.fsp.plugin.inventory.api.ApiProblemException;
import com.fsp.plugin.inventory.api.InventoryDtos;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;

/** Paged, sorted, searched collection plumbing (AGENTS §2f) shared by all inventory services. */
public final class Paging {

    private Paging() {}

    /** Public sort name → pinned column (never a client-supplied column). */
    public static Pageable page(Map<String, String> sortColumns, String sort, int pageNum, int size) {
        if (pageNum < 0 || size < 1 || size > 100) {
            throw ApiProblemException.badRequest("BOUNDS", "page >= 0 and size within 1-100");
        }
        String s = sort == null || sort.isBlank() ? "createdAt,desc" : sort.trim();
        String column = sortColumns.get(column(s));
        if (column == null) {
            throw ApiProblemException.badRequest("BOUNDS", "sort must be one of " + sortColumns.keySet());
        }
        Sort.Direction direction = s.toLowerCase().endsWith(",asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(pageNum, size, Sort.by(direction, column));
    }

    private static String column(String s) {
        return s.split(",")[0].trim();
    }

    /** Closed status filter: unknown values → 400 (guide §4A). */
    public static String status(String status, List<String> valid) {
        if (status == null || status.isBlank()) return null;
        if (!valid.contains(status)) {
            throw ApiProblemException.badRequest("BOUNDS", "Unknown status '" + status + "'");
        }
        return status;
    }

    /** Totals-free page envelope (plan §12.4): opaquely, nextCursor = the page number. */
    public static <T> InventoryDtos.ItemList<T> pageOf(Page<T> p, int size) {
        List<T> items = p.getContent();
        int upTo = Math.min(items.size(), size);
        boolean hasMore = items.size() > size;
        return new InventoryDtos.ItemList<>(items.subList(0, upTo), hasMore,
            hasMore ? "page-" + (p.getNumber() + 1) : null, p.getSort().toString());
    }
}
