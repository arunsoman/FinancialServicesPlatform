package com.fsp.core.identity;

import com.fsp.core.common.ApiException;
import com.fsp.core.rbac.Permission;
import com.fsp.core.rbac.PermissionRepository;
import com.fsp.core.rbac.Role;
import com.fsp.core.rbac.RolePermission;
import com.fsp.core.rbac.RolePermissionRepository;
import com.fsp.core.rbac.RoleRepository;
import com.fsp.core.rbac.UserRole;
import com.fsp.core.rbac.RbacRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Person + role creation helpers shared by tenant creation and KYC onboarding.
 * Runs inside the caller's transaction (REQUIRED).
 */
@Slf4j
@Service
public class PersonProvisioning {

    private final PersonRepository personRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RbacRepository rbacRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public PersonProvisioning(PersonRepository personRepository, RoleRepository roleRepository,
                              PermissionRepository permissionRepository, RbacRepository rbacRepository,
                              RolePermissionRepository rolePermissionRepository) {
        this.personRepository = personRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.rbacRepository = rbacRepository;
        this.rolePermissionRepository = rolePermissionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Person createPerson(UUID tenantId, String username, String fullName, String email,
                               String rawPassword, boolean applicant, boolean mustChangePassword) {
        if (username == null || username.isBlank()) {
            throw ApiException.badRequest("USERNAME_INVALID", "username required");
        }
        if (personRepository.existsByUsername(username)) {
            throw ApiException.conflict("USERNAME_TAKEN", "username '%s' already exists".formatted(username));
        }
        Person p = new Person();
        p.id = UUID.randomUUID();
        p.tenantId = tenantId;
        p.username = username;
        p.fullName = fullName == null || fullName.isBlank() ? username : fullName;
        p.email = email;
        p.passwordHash = encoder.encode(rawPassword);
        p.mustChangePassword = mustChangePassword;
        p.status = "ACTIVE";
        p.isApplicant = applicant;
        Person saved = personRepository.save(p);
        log.info("person_created personId={} tenant={} username={} applicant={} mustChangePassword={}",
            saved.id, tenantId, username, applicant, mustChangePassword);
        return saved;
    }

    /**
     * V36: a person who sets their OWN password through a one-time activation sent to their
     * primary contact (IdP link by email, code by SMS). Born with a random 256-bit secret
     * nobody knows — not the shared bootstrap password — so no one (maker, admin, operator)
     * can sign in as them, and they cannot sign in before activating. The caller triggers
     * {@link IdpInternalClient#triggerActivation} AFTER its transaction commits.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Person createInvitedPerson(UUID tenantId, String username, String fullName, String email,
                                      String phone, String primaryContact, String status,
                                      boolean applicant, boolean customer) {
        if (username == null || username.isBlank()) {
            throw ApiException.badRequest("USERNAME_INVALID", "username required");
        }
        if (personRepository.existsByUsername(username)) {
            throw ApiException.conflict("USERNAME_TAKEN", "username '%s' already exists".formatted(username));
        }
        Contact c = contact(email, phone, primaryContact);
        Person p = new Person();
        p.id = UUID.randomUUID();
        p.tenantId = tenantId;
        p.username = username;
        p.fullName = fullName == null || fullName.isBlank() ? username : fullName.trim();
        p.email = c.email();
        p.phone = c.phone();
        p.primaryContact = c.primary();
        p.passwordHash = encoder.encode(unguessableSecret());
        p.mustChangePassword = false; // the activation IS the password set-up
        p.status = status;
        p.isApplicant = applicant;
        p.isCustomer = customer;
        Person saved = personRepository.save(p);
        log.info("person_invited personId={} tenant={} applicant={} customer={} status={} channel={}",
            saved.id, tenantId, applicant, customer, status, c.primary());
        return saved;
    }

    /** Validated contact set: the primary channel must carry a usable address. */
    public record Contact(String email, String phone, String primary) {}

    public static Contact contact(String email, String phone, String primaryContact) {
        String e = email == null || email.isBlank() ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
        String ph = null;
        if (phone != null && !phone.isBlank()) {
            ph = com.fsp.core.notify.channel.ChannelAddresses.normaliseE164(phone);
            if (ph == null) {
                throw ApiException.badRequest("PHONE_INVALID",
                    "Phone must be in international format, e.g. +264 81 123 4567");
            }
        }
        if (e != null && !com.fsp.core.notify.channel.ChannelAddresses.isPlausibleEmail(e)) {
            throw ApiException.badRequest("EMAIL_INVALID", "Enter a valid email address");
        }
        String primary = primaryContact == null || primaryContact.isBlank()
            ? (e != null ? "EMAIL" : "PHONE") : primaryContact.trim().toUpperCase(java.util.Locale.ROOT);
        if (!primary.equals("EMAIL") && !primary.equals("PHONE")) {
            throw ApiException.badRequest("PRIMARY_CONTACT_INVALID", "Primary contact must be EMAIL or PHONE");
        }
        if (primary.equals("EMAIL") && e == null) {
            throw ApiException.badRequest("CONTACT_REQUIRED", "An email address is required when email is the primary contact");
        }
        if (primary.equals("PHONE") && ph == null) {
            throw ApiException.badRequest("CONTACT_REQUIRED", "A phone number is required when phone is the primary contact");
        }
        return new Contact(e, ph, primary);
    }

    private static String unguessableSecret() {
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        return java.util.HexFormat.of().formatHex(raw);
    }

    /** Create (idempotent per tenant+key) a scoped role and wire the permission keys. */
    @Transactional(propagation = Propagation.REQUIRED)
    public Role ensureRole(UUID tenantId, String key, String name, String scope, List<String> permissionKeys) {
        Role role = roleRepository.findByTenantId(tenantId).stream()
            .filter(r -> key.equals(r.key))
            .findFirst()
            .orElseGet(() -> {
                Role r = new Role();
                r.id = UUID.randomUUID();
                r.tenantId = tenantId;
                r.key = key;
                r.name = name;
                r.scope = scope;
                Role saved = roleRepository.save(r);
                log.info("role_created roleId={} tenant={} key={} scope={}", saved.id, tenantId, key, scope);
                return saved;
            });
        for (String permKey : permissionKeys) {
            // validate against the global catalog so typos fail fast
            Permission catalogEntry = permissionRepository.findById(permKey)
                .orElseThrow(() -> ApiException.badRequest("PERMISSION_UNKNOWN",
                    "Unknown permission key '%s'".formatted(permKey)));
            boolean linked = rbacRepository.existsRolePermission(role.id, catalogEntry.key);
            if (!linked) {
                RolePermission rp = new RolePermission();
                rp.roleId = role.id;
                rp.permissionKey = catalogEntry.key;
                rolePermissionRepository.save(rp);
            }
        }
        return role;
    }

    /**
     * Marks a provisioned person as a designated checker (persons.is_checker). Only for the
     * checker an organization is BORN with (PSP creation / KYC saga) — every later change goes
     * through the four-eyes USER_CREATE / USER_UPDATE flow (docs/checker-flag.md).
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Person designateChecker(Person person) {
        person.isChecker = true;
        Person saved = personRepository.save(person);
        log.info("person_designated_checker personId={} tenant={}", saved.id, saved.tenantId);
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void assignRole(UUID personId, UUID roleId) {
        if (rbacRepository.findByPersonId(personId).stream().anyMatch(ur -> ur.roleId.equals(roleId))) {
            return;
        }
        UserRole ur = new UserRole();
        ur.personId = personId;
        ur.roleId = roleId;
        rbacRepository.save(ur);
        log.info("role_assigned personId={} roleId={}", personId, roleId);
    }
}
