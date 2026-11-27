package com.fsp.emi.wallet;

import com.fsp.emi.common.Uuid5;
import com.fsp.emi.ledger.EmiTbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Real-TigerBeetle smoke (run with --spring.profiles.active=demo-wallet): open, deposit,
 * redeem, balance + float check. Idempotent by unique statement ref; runtime-only demo
 * data (platform rule: demo data lives only in demo seed/profiles, never SQL seed).
 */
@Component
@Profile("demo-wallet")
final class EmiDemoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmiDemoRunner.class);

    private final EmiWalletService wallets;
    private final EmiTbService tb;

    EmiDemoRunner(EmiWalletService wallets, EmiTbService tb) {
        this.wallets = wallets;
        this.tb = tb;
    }

    @Override
    public void run(ApplicationArguments args) {
        var wallet = wallets.open(UUID.randomUUID(), "demo-" + UUID.randomUUID());
        var deposit = wallets.deposit(wallet.id, 100_00, "DEMO-" + UUID.randomUUID());
        var redeem = wallets.redeem(wallet.id, 25_00);
        var f = tb.lookupBalance(Uuid5.floatAccountId())
            .map(b -> b.debitsPosted() - b.creditsPosted()).orElse(-1L);
        log.info("emi_demo_open id={} tb={} balance={}", wallet.id, wallet.tbAccountId,
            wallets.balance(wallet.id));
        log.info("emi_demo_deposit ref={} state={} | redeem state={}",
            deposit.reference, deposit.state, redeem.state);
        log.info("emi_demo_float net={} (expected >= 75_00 and == sum of wallet balances)", f);
    }
}
