# Concurrency, Race Condition & Deadlock Playbook

This reference provides patterns and anti-patterns for reviewing backend Java and database code in FSP.

---

## 1. PostgreSQL Deadlocks (`40P01`)

### Anti-Pattern: Inconsistent Row-Lock Ordering
```java
// Thread 1:
updateAccount(accountA); // locks row A
updateAccount(accountB); // attempts to lock row B -> blocks or deadlocks

// Thread 2 (concurrent):
updateAccount(accountB); // locks row B
updateAccount(accountA); // attempts to lock row A -> DEADLOCK detected by Postgres!
```

### Required Pattern: Deterministic Sorted Order
Whenever multiple rows are locked or updated, sort them deterministically by primary key:
```java
List<Account> accounts = fetchAccounts(ids);
accounts.sort(Comparator.comparing(Account::getId));

for (Account acc : accounts) {
    acc.applyUpdate(...);
    accountRepository.save(acc);
}
```

---

## 2. Long Transaction Starvation & Lock Holding

### Anti-Pattern: External I/O inside `@Transactional`
```java
@Transactional
public void approveAndDispatch(UUID entryId) {
    JournalEntry entry = repository.findById(entryId).orElseThrow();
    entry.setStatus(SUBMITTING);
    repository.save(entry); // DB row lock held!

    // REMOTE NETWORK CALL: Can take 500ms - 10s or timeout!
    TbResult result = tbService.submitTransfers(entry.getPlan());

    entry.setStatus(COMMITTED);
    repository.save(entry);
}
```
**Danger:** While awaiting `tbService` or external HTTP responses, PostgreSQL row locks and DB connection pool threads remain blocked. This starves the pool and cascades into system-wide deadlocks.

### Required Pattern: Split Phases
```java
// Phase 1: Persist plan & transition to SUBMITTING in DB tx
public void prepareSubmission(UUID entryId) {
    journalTx.markSubmitting(entryId); // Fast DB transaction
}

// Phase 2: In-Memory / Remote Dispatch (outside DB transaction)
public void executeDispatch(UUID entryId) {
    KeyLocks.withLock("je-approval", entryId, () -> {
        JournalPlan plan = loadPlan(entryId);
        TbResult result = tbService.submitTransfers(plan); // Network I/O outside DB tx!
        journalTx.finalizeResult(entryId, result); // Fast DB transaction
    });
}
```

---

## 3. JVM Concurrency & In-Memory Deadlocks

### Anti-Pattern: Synchronizing on Spring Beans or Strings
```java
@Service
public class PaymentService {
    // DEADLOCK & BOTTLENECK: Blocks all threads across the entire service
    public synchronized void processPayment() { ... }

    // DANGEROUS: String literals are interned across the entire JVM
    public void processTenant(String tenantId) {
        synchronized(tenantId) { ... }
    }
}
```

### Required Pattern: Striped Fair KeyLocks
```java
public void processTenant(UUID tenantId) {
    KeyLocks.withLock("tenant-action", tenantId, () -> {
        // executes in fair ReentrantLock per tenantId
    });
}
```

---

## 4. Lost Updates & State Machine Race Conditions

### Anti-Pattern: Read-Modify-Write Without Versioning
```java
// Thread 1 and Thread 2 both read balance = 100
Account acc = repository.findById(id).get();
acc.setBalance(acc.getBalance() + amount);
repository.save(acc); // Thread 2 overwrites Thread 1's write!
```

### Required Pattern: Optimistic Locking + Atomic Preconditions
1. Add `@Version private Long version;` on the aggregate root.
2. In update queries, check status preconditions:
```java
@Modifying
@Query("UPDATE JournalEntry j SET j.status = :toStatus, j.version = j.version + 1 " +
       "WHERE j.id = :id AND j.status = :fromStatus AND j.version = :expectedVersion")
int transitionStatus(@Param("id") UUID id, ...);
```
If returning 0 rows updated, throw conflict exception immediately.
