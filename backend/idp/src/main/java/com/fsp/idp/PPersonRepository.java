package com.fsp.idp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PPersonRepository extends JpaRepository<PPerson, UUID> {
    Optional<PPerson> findByUsername(String username);

    /** V36: persons awaiting an SMS activation on this E.164 phone (normally one). */
    java.util.List<PPerson> findByPhoneAndPrimaryContactAndContactVerifiedAtIsNull(String phone, String primaryContact);
}
