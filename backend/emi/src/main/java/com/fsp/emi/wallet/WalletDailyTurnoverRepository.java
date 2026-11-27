package com.fsp.emi.wallet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface WalletDailyTurnoverRepository extends JpaRepository<WalletDailyTurnover, WalletDailyTurnover.Pk> {

    Optional<WalletDailyTurnover> findByWalletIdAndTurnDate(UUID walletId, LocalDate turnDate);
}
