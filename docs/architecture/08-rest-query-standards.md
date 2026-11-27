# Architecture Chapter 8 — REST Collection Standards (Pagination, Sorting, Search)

**Parent Document:** [docs/architecture.md](../architecture.md)

---

## 1. Universal Mandate for Collection Endpoints

Every REST controller endpoint that fetches a collection or query dataset from the server — **regardless of whether it resides in Core or an Out-of-Process Plugin** — MUST support:
1. **Server-side Pagination by default**
2. **Server-side Sorting by default**
3. **Server-side Search & Filtering by default**

Unbounded list endpoints (e.g. returning all rows into memory) and client-side sorting/filtering of entire collections are strictly forbidden.

---

## 2. API Contract Specification

### 2.1 Request Parameters

| Parameter | Type | Default | Constraint / Validation | Description |
|---|---|---|---|---|
| `page` | Integer | `0` | `min = 0` | 0-indexed page number |
| `size` | Integer | `20` | `1 <= size <= 100` | Bounded page size |
| `sort` | String | `createdAt,desc` | Safelist validated | Property name and direction (`asc` or `desc`), comma-separated |
| `q` | String | null | Sanitized string, max 100 chars | Free-text search query across searchable text fields |
| `<domain filters>` | Typed | null | Whitelist per endpoint | Domain-specific filters (e.g., `status`, `tenantId`, `fromDate`, `toDate`) |

### 2.2 Response Envelope

Collection responses must return bounded pagination metadata:

```json
{
  "items": [
    { /* record fields */ }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 142,
  "totalPages": 8,
  "hasNext": true
}
```

---

## 3. Implementation Rules

### 3.1 Backend (Spring Boot Core & Plugins)

1. **Spring Data `Pageable` & `Page<T>`:** Controllers accept a `Pageable` instance (or explicit `@RequestParam` bindings mapped to `PageRequest.of(page, size, Sort.by(...))`).
2. **Sort Safelist Mapping:**
   - Controllers/Services MUST validate incoming sort property names against a domain safelist before passing them to JPA/Hibernate.
   - Example safelist:
     ```java
     private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "createdAt", "status", "name");
     ```
   - Sort requests referencing non-whitelisted properties fail with `400 BAD_REQUEST (INVALID_SORT_FIELD)`.
3. **Push-Down to Database Queries:**
   - Search query `q` and domain filter parameters MUST be pushed into the database query layer (using Spring Data JPA Specifications, QueryDSL, or Criteria API).
   - Java stream-based filtering in service or controller memory after fetching from the DB is prohibited for API endpoints.

### 3.2 Frontend & UI Portals

1. **URL-First Query State:**
   - Active page, sort column, sort direction, and search keyword MUST synchronize with URL search parameters (e.g. `?page=0&sort=createdAt,desc&q=term`).
2. **TanStack Query Key Alignment:**
   - TanStack Query cache keys MUST include all active pagination and query parameters:
     ```typescript
     useQuery({
       queryKey: ['cases', { page, size, sort, q, status }],
       queryFn: () => fetchCases({ page, size, sort, q, status })
     });
     ```
3. **No In-Memory Slicing:**
   - UI tables consume the `items` array and render server-driven pagination controls (`totalPages`, `hasNext`, `totalElements`).
