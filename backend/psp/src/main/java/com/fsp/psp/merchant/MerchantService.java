package com.fsp.psp.merchant;

import com.fsp.psp.common.ApiException;
import com.fsp.psp.common.KeyLocks;
import com.fsp.psp.config.PspProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Merchant onboarding and lifecycle (plan §11 Stage 5). PENDING → ACTIVE → SUSPENDED.
 * A merchant can only acquire (and be settled) while ACTIVE. Tenant-subtree scoping is
 * enforced HERE — the merchant row belongs to the actor's tenant or it is a 404.
 */
@Service
public class MerchantService {

    private static final Set<String> SORTABLE = Set.of("createdAt", "merchantCode", "displayName",
        "status", "payOverDays");

    private final MerchantRepository merchants;
    private final PspProperties props;
    private final KeyLocks locks;

    public MerchantService(MerchantRepository merchants, PspProperties props, KeyLocks locks) {
        this.merchants = merchants;
        this.props = props;
        this.locks = locks;
    }

    @Transactional
    public Merchant onboard(UUID tenantId, String merchantCode, String displayName,
                            String bankAccountRef, Integer payOverDays) {
        if (merchantCode == null || merchantCode.isBlank() || merchantCode.length() > 64
                || !merchantCode.matches("[A-Za-z0-9_-]+")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_INPUT_INVALID",
                "merchantCode must be [A-Za-z0-9_-]{1,64}");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_INPUT_INVALID",
                "displayName is required (≤200 chars)");
        }
        int payOver = payOverDays == null ? props.getSettlement().getPayOverDays() : payOverDays;
        if (payOver < 1 || payOver > 2) {
            // PSD-1 §12.2.3 — pay-over is a regulatory ceiling, not a free knob.
            throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_INPUT_INVALID",
                "payOverDays must be 1 or 2 (PSD-1 §12.2.3 ceiling)");
        }

        return locks.withLock("merchant-code", merchantCode, () -> {
            if (merchants.findByMerchantCode(merchantCode).isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, "PSP_MERCHANT_CODE_TAKEN",
                    "merchantCode already exists");
            }
            Merchant m = new Merchant();
            m.id = UUID.randomUUID();
            m.tenantId = tenantId;
            m.merchantCode = merchantCode;
            m.displayName = displayName.strip();
            m.merchantBankAccountRef = bankAccountRef;
            m.payOverDays = payOver;
            m.status = Merchant.STATUS_PENDING;
            m.createdAt = Instant.now();
            m.updatedAt = m.createdAt;
            return merchants.save(m);
        });
    }

    public Merchant byId(UUID tenantId, UUID merchantId) {
        return merchants.findByIdAndTenantId(merchantId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PSP_MERCHANT_NOT_FOUND",
                "merchant not found"));
    }

    @Transactional
    public Merchant activate(UUID tenantId, UUID merchantId) {
        return transition(tenantId, merchantId, Merchant.STATUS_PENDING, Merchant.STATUS_ACTIVE,
            "MERCHANT_ACTIVATED");
    }

    @Transactional
    public Merchant suspend(UUID tenantId, UUID merchantId) {
        return transition(tenantId, merchantId, Merchant.STATUS_ACTIVE, Merchant.STATUS_SUSPENDED,
            "MERCHANT_SUSPENDED");
    }

    private Merchant transition(UUID tenantId, UUID merchantId, String expected, String target,
                                String event) {
        return locks.withLock("merchant", merchantId, () -> {
            Merchant m = byId(tenantId, merchantId);
            if (!expected.equals(m.status)) {
                throw new ApiException(HttpStatus.CONFLICT, "PSP_MERCHANT_STATE",
                    "merchant is " + m.status + ", expected " + expected);
            }
            m.status = target;
            m.updatedAt = Instant.now();
            return merchants.save(m);
        });
    }

    /** Tenant-scoped, server-side paginated/sorted/filtered merchant list (platform §2f). */
    public Page<Merchant> list(UUID tenantId, int page, int size, String sort, String q) {
        return merchants.search(tenantId, (q == null || q.isBlank()) ? null : q.strip(),
            pageOf(page, size, sort));
    }

    public static Pageable pageOf(int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_PAGE_INVALID",
                "page must be ≥0 and size in 1..100");
        }
        String field = "createdAt";
        Sort.Direction direction = Sort.Direction.DESC;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", 2);
            if (!SORTABLE.contains(parts[0])) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_SORT_INVALID",
                    "sort field not permitted");
            }
            field = parts[0];
            if (parts.length == 2 && "asc".equalsIgnoreCase(parts[1].strip())) {
                direction = Sort.Direction.ASC;
            } else if (parts.length == 2 && "desc".equalsIgnoreCase(parts[1].strip())) {
                direction = Sort.Direction.DESC;
            } else if (parts.length == 2) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_SORT_INVALID",
                    "sort direction must be asc|desc");
            }
        }
        return PageRequest.of(page, size, Sort.by(direction, field));
    }

    /** A suspended merchant cannot receive new authorizations. */
    public static void requireAcquirable(Merchant m) {
        if (!Merchant.STATUS_ACTIVE.equals(m.status)) {
            throw new ApiException(HttpStatus.CONFLICT, "PSP_MERCHANT_NOT_ACQUIRABLE",
                "merchant is " + m.status + "; only ACTIVE merchants can acquire");
        }
    }

    /** Demo-profile helper: fetch-or-create (idempotent re-runs). */
    @Transactional
    public Merchant maybeOnboard(UUID tenantId, String merchantCode, String displayName) {
        return locks.withLock("merchant-code", merchantCode, () ->
            merchants.findByMerchantCode(merchantCode).orElseGet(()
                -> onboard(tenantId, merchantCode, displayName, null, null)));
    }

    /** Demo-profile helper: activate a PENDING merchant without failing on ACTIVE. */
    @Transactional
    public void activateIfPending(UUID tenantId, UUID merchantId) {
        Merchant m = byId(tenantId, merchantId);
        if (Merchant.STATUS_PENDING.equals(m.status)) {
            activate(tenantId, merchantId);
        }
    }
}
