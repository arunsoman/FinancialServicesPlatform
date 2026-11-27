package com.fsp.core.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.ledger.BankAccount;
import com.fsp.core.ledger.BankAccountRepository;
import com.fsp.core.ledger.BankAccountService;
import com.fsp.core.orders.EmiMoneyClient;
import com.fsp.core.orders.SkuOrderService;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * How a buyer settles an invoice.
 *
 * <ul>
 *   <li><b>WALLET</b> — the paying person's own NAD e-money wallet at the EMI: a charge (linked hold)
 *       then its confirm, both through {@link EmiMoneyClient}. EMI's ledger (TigerBeetle) moves the
 *       money; core records the outcome. SUCCEEDED at once, or FAILED with EMI's code, or
 *       COMMIT_UNKNOWN when the rail did not answer (the amount stays reserved).</li>
 *   <li><b>BANK_TRANSFER</b> — the buyer pays outside the platform quoting the invoice number; the
 *       payment is AWAITING_CONFIRMATION until the seller confirms the money arrived.</li>
 *   <li><b>CARD</b> — offered only once a card rail with a hosted payment page is connected; until
 *       then the option says so and a CARD payment is refused (no card data ever reaches core).</li>
 * </ul>
 *
 * Concurrency (AGENTS §2d): the reservation (payment row + invoice version bump) is one short
 * transaction; the EMI calls run with NO transaction and NO lock held; the outcome is a second
 * short transaction, retried on an optimistic-lock race because the money has already moved.
 * Every in-flight payment reserves its amount, so concurrent payments can never exceed the balance.
 */
@Slf4j
@Service
public class InvoicePaymentService {

    static final String CARD_UNAVAILABLE = "Card payments are not switched on yet — pay from your wallet or by bank transfer.";
    private static final int SETTLE_ATTEMPTS = 3;

    private final InvoiceService invoicesService;
    private final InvoiceRepository invoices;
    private final InvoicePaymentRepository payments;
    private final BankAccountRepository bankAccounts;
    private final EmiMoneyClient emi;
    private final PermissionService permissions;
    private final AuditService audit;
    private final OutboxService outbox;
    private final TransactionTemplate tx;

    public InvoicePaymentService(InvoiceService invoicesService, InvoiceRepository invoices,
                                 InvoicePaymentRepository payments, BankAccountRepository bankAccounts,
                                 EmiMoneyClient emi, PermissionService permissions, AuditService audit,
                                 OutboxService outbox, PlatformTransactionManager txManager) {
        this.invoicesService = invoicesService;
        this.invoices = invoices;
        this.payments = payments;
        this.bankAccounts = bankAccounts;
        this.emi = emi;
        this.permissions = permissions;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txManager);
    }

    // ============================================================ options

    /** What this buyer can pay this invoice with — decided here, never in the browser. */
    public List<InvoiceDtos.PaymentOption> options(UUID tenantId, Actor actor, UUID invoiceId) {
        permissions.require(actor, "invoice.pay", tenantId);
        Invoice inv = tx.execute(s -> invoicesService.asBuyer(tenantId, invoiceId));
        return List.of(walletOption(inv, actor), transferOption(inv), cardOption());
    }

    private InvoiceDtos.PaymentOption walletOption(Invoice inv, Actor actor) {
        if (!"NAD".equals(inv.currency)) {
            return unavailable(InvoicePayment.Method.WALLET, "E-money wallets pay NAD invoices only.");
        }
        Optional<JsonNode> wallet;
        try {
            wallet = emi.myWallet(actor.personId(), actor.activeTenantId(), actor.username());
        } catch (RuntimeException e) {
            log.warn("invoice_wallet_lookup_failed invoice={} error={}", inv.id, e.getClass().getSimpleName());
            return unavailable(InvoicePayment.Method.WALLET, "The wallet service is not answering — try again shortly.");
        }
        if (wallet.isEmpty()) return unavailable(InvoicePayment.Method.WALLET, "You do not have an e-money wallet yet.");
        if (!"ACTIVE".equals(wallet.get().path("status").asText("ACTIVE"))) {
            return unavailable(InvoicePayment.Method.WALLET, "Your wallet is not active.");
        }
        String balance = wallet.get().path("balance").asText("0");
        boolean enough = new BigInteger(balance.matches("-?\\d+") ? balance : "0").compareTo(BigInteger.valueOf(inv.balanceMinor())) >= 0;
        return new InvoiceDtos.PaymentOption(InvoicePayment.Method.WALLET, true,
            enough ? null : "Your wallet covers part of this — pay part now, the rest later.", balance, null, null);
    }

    private InvoiceDtos.PaymentOption transferOption(Invoice inv) {
        Optional<BankAccount> account = sellerAccount(inv);
        if (account.isEmpty()) {
            return unavailable(InvoicePayment.Method.BANK_TRANSFER, "The seller has no approved bank account for " + inv.currency + " yet.");
        }
        BankAccount a = account.get();
        InvoiceDtos.Payee payee = new InvoiceDtos.Payee(a.holderName,
            BankAccountService.BANKS.getOrDefault(a.bankCode, a.bankCode), InvoiceMath.accountEnding(a.accountNumber));
        return new InvoiceDtos.PaymentOption(InvoicePayment.Method.BANK_TRANSFER, true, null, null, payee, inv.number);
    }

    private static InvoiceDtos.PaymentOption cardOption() {
        return unavailable(InvoicePayment.Method.CARD, CARD_UNAVAILABLE);
    }

    private static InvoiceDtos.PaymentOption unavailable(String method, String reason) {
        return new InvoiceDtos.PaymentOption(method, false, reason, null, null, null);
    }

    private Optional<BankAccount> sellerAccount(Invoice inv) {
        return bankAccounts.findByTenantIdAndStatus(inv.sellerTenantId, BankAccount.Status.ACTIVE).stream()
            .filter(a -> inv.currency.equals(a.currency)).findFirst();
    }

    // ============================================================ pay

    public InvoiceDtos.PaymentView pay(UUID tenantId, Actor payer, UUID invoiceId, InvoiceDtos.PayRequest in) {
        permissions.require(payer, "invoice.pay", tenantId);
        InvoiceService.requirePerson(payer);
        if (in == null) throw ApiException.badRequest("BODY_INVALID", "Request body required");
        String method = in.method() == null ? "" : in.method().trim().toUpperCase(Locale.ROOT);
        if (!InvoicePayment.Method.ALL.contains(method)) {
            throw ApiException.badRequest("PAYMENT_INVALID", "method must be WALLET, BANK_TRANSFER or CARD");
        }
        if (in.amountMinor() == null || !in.amountMinor().matches("[1-9]\\d{0,15}")) {
            throw ApiException.badRequest("PAYMENT_INVALID", "amountMinor must be a positive whole number of minor units");
        }
        long amount = Long.parseLong(in.amountMinor());

        return switch (method) {
            case InvoicePayment.Method.CARD -> {
                log.warn("invoice_payment_refused invoice={} method=CARD reason=no_card_rail", invoiceId);
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_METHOD_UNAVAILABLE", CARD_UNAVAILABLE);
            }
            case InvoicePayment.Method.BANK_TRANSFER -> InvoiceService.paymentView(tx.execute(s -> {
                Invoice inv = invoicesService.asBuyer(tenantId, invoiceId);
                if (sellerAccount(inv).isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_METHOD_UNAVAILABLE",
                        "The seller has no approved bank account for " + inv.currency + " yet.");
                }
                return reserve(inv, payer, in.expectedVersion(), method, amount, InvoicePayment.Status.AWAITING_CONFIRMATION);
            }));
            default -> payFromWallet(tenantId, payer, invoiceId, in.expectedVersion(), amount);
        };
    }

    private InvoiceDtos.PaymentView payFromWallet(UUID tenantId, Actor payer, UUID invoiceId, Long expectedVersion, long amount) {
        // 1 · checks that need no lock: the invoice is payable in NAD, the person has a wallet that covers it
        Invoice peek = tx.execute(s -> invoicesService.asBuyer(tenantId, invoiceId));
        if (!"NAD".equals(peek.currency)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_METHOD_UNAVAILABLE", "E-money wallets pay NAD invoices only.");
        }
        JsonNode wallet = emi.myWallet(payer.personId(), payer.activeTenantId(), payer.username())
            .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WALLET_MISSING",
                "You do not have an e-money wallet yet."));
        String bal = wallet.path("balance").asText("0");
        if (!bal.matches("\\d+") || new BigInteger(bal).compareTo(BigInteger.valueOf(amount)) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WALLET_INSUFFICIENT",
                "Your wallet balance does not cover that amount.");
        }
        UUID walletId = UUID.fromString(wallet.path("id").asText());

        // 2 · reserve: the payment row (PENDING) + the invoice version bump, one short transaction
        InvoicePayment reserved = tx.execute(s -> reserve(invoicesService.asBuyer(tenantId, invoiceId), payer,
            expectedVersion, InvoicePayment.Method.WALLET, amount, InvoicePayment.Status.PENDING));

        // 3 · the rail — no transaction, no lock
        String orderRef = "inv-pay:" + reserved.id;
        UUID chargeId = null;
        try {
            chargeId = emi.charge(walletId, payer.personId(), payer.activeTenantId(), payer.username(), orderRef,
                java.math.BigDecimal.valueOf(amount), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "NAD",
                SkuOrderService.fingerprintOf(walletId, orderRef, amount, 0, 0, "NAD"));
            final UUID held = chargeId;
            tx.executeWithoutResult(s -> payments.findById(reserved.id).ifPresent(p -> {
                p.railRef = held.toString();
                p.reference = "EMI-" + held.toString().substring(0, 8).toUpperCase(Locale.ROOT);
                payments.saveAndFlush(p);
            }));
            emi.confirm(chargeId, payer.personId(), payer.activeTenantId(), payer.username());
        } catch (ApiException refused) {
            if (refused.getStatus().is4xxClientError()) {
                if (chargeId != null) releaseHold(chargeId, payer);
                fail(reserved.id, refused.getCode());
                log.warn("invoice_wallet_payment_refused invoice={} payment={} code={}", invoiceId, reserved.id, refused.getCode());
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, refused.getCode(), refused.getMessage());
            }
            return unknown(reserved.id, invoiceId, refused);
        } catch (RuntimeException transport) {
            return unknown(reserved.id, invoiceId, transport);
        }

        // 4 · the money moved: record it (retry the bookkeeping on a version race — never lose a payment)
        for (int attempt = 1; ; attempt++) {
            try {
                return InvoiceService.paymentView(tx.execute(s -> {
                    InvoicePayment p = payments.findById(reserved.id).orElseThrow();
                    Invoice inv = invoices.findById(invoiceId).orElseThrow();
                    invoicesService.settled(inv, p);
                    audit.record(payer.personId(), null, tenantId, null, "INVOICE_PAID_FROM_WALLET", "InvoicePayment",
                        p.id.toString(), null, audit.hashState("amount=" + p.amountMinor + ";status=SUCCEEDED"), false);
                    return p;
                }));
            } catch (OptimisticLockingFailureException race) {
                if (attempt >= SETTLE_ATTEMPTS) {
                    log.error("invoice_wallet_settle_unrecorded invoice={} payment={} charge={}", invoiceId, reserved.id, chargeId, race);
                    markUnknown(reserved.id);
                    throw race;
                }
                log.warn("invoice_wallet_settle_retry invoice={} payment={} attempt={}", invoiceId, reserved.id, attempt);
            }
        }
    }

    /** Insert the payment and bump the invoice version (caller holds the transaction). */
    private InvoicePayment reserve(Invoice inv, Actor payer, Long expectedVersion, String method, long amount, String status) {
        InvoiceService.expectVersion(inv, expectedVersion);
        if (!inv.isOpen()) throw InvoiceService.stateConflict(inv, "This invoice is not open for payment.");
        long inFlight = payments.sumByStatus(inv.id, InvoicePayment.Status.IN_FLIGHT);
        if (amount > inv.balanceMinor() - inFlight) {
            log.warn("invoice_payment_exceeds_balance invoice={} amount={} balance={} inFlight={}",
                inv.id, amount, inv.balanceMinor(), inFlight);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_EXCEEDS_BALANCE",
                inFlight > 0 ? "That is more than the balance not already being paid." : "That is more than the balance due.");
        }
        InvoicePayment p = new InvoicePayment();
        p.id = UUID.randomUUID();
        p.invoiceId = inv.id;
        p.method = method;
        p.amountMinor = amount;
        p.status = status;
        p.reference = InvoicePayment.Method.BANK_TRANSFER.equals(method) ? inv.number : null;
        p.payerPersonId = payer.personId();
        payments.saveAndFlush(p);
        inv.updatedAt = java.time.Instant.now(); // a payment changes what others may do → new version
        invoices.saveAndFlush(inv);
        audit.record(payer.personId(), null, inv.buyerTenantId, null, "INVOICE_PAYMENT_STARTED", "InvoicePayment",
            p.id.toString(), null, audit.hashState("method=" + method + ";amount=" + amount + ";status=" + status), false);
        outbox.record("Invoice", inv.id, "Invoice.PaymentStarted",
            "{\"invoice\":\"%s\",\"payment\":\"%s\",\"method\":\"%s\",\"amountMinor\":\"%d\",\"currency\":\"%s\",\"status\":\"%s\"}"
                .formatted(inv.id, p.id, method, amount, inv.currency, status));
        log.info("invoice_payment_started invoice={} payment={} method={} amount={} status={}", inv.id, p.id, method, amount, status);
        return p;
    }

    private void fail(UUID paymentId, String code) {
        tx.executeWithoutResult(s -> payments.findById(paymentId).ifPresent(p -> {
            p.status = InvoicePayment.Status.FAILED;
            p.failureCode = code;
            payments.saveAndFlush(p);
        }));
    }

    private InvoiceDtos.PaymentView unknown(UUID paymentId, UUID invoiceId, RuntimeException cause) {
        log.error("invoice_wallet_commit_unknown invoice={} payment={} error={}", invoiceId, paymentId,
            cause.getClass().getSimpleName(), cause);
        return InvoiceService.paymentView(markUnknown(paymentId));
    }

    private InvoicePayment markUnknown(UUID paymentId) {
        return tx.execute(s -> {
            InvoicePayment p = payments.findById(paymentId).orElseThrow();
            p.status = InvoicePayment.Status.COMMIT_UNKNOWN;
            return payments.saveAndFlush(p);
        });
    }

    /** Best effort: a hold whose confirm was refused is released; if that fails too, EMI's recon sees it. */
    private void releaseHold(UUID chargeId, Actor payer) {
        try {
            emi.voidCharge(chargeId, payer.personId(), payer.activeTenantId(), payer.username());
        } catch (RuntimeException e) {
            log.error("invoice_wallet_hold_release_failed charge={} error={}", chargeId, e.getClass().getSimpleName(), e);
        }
    }
}
