package com.fsp.emi.wallet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public interface WalletJournalRepository extends JpaRepository<WalletJournal, UUID> {

    Optional<WalletJournal> findByKindAndReference(String kind, String reference);

    Stream<WalletJournal> findByWalletIdAndState(UUID walletId, String state);
}
