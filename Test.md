# Silent Data Loss After an ORM Version Upgrade: A Case Study

## Summary

A years-old, well-understood, and deliberately ignored database error
started silently discarding unrelated writes in the same transaction after a
Hibernate/Spring version upgrade — with no exception, no log warning, and a
transaction that reports a clean commit. This is a write-up of the
mechanism, the diagnostic path that found it, and the fix, generalized away
from any specific application.

---

## The Setup

- A Spring service method annotated `@Transactional`, wrapping several
  sequential persistence operations against the same Hibernate session:
  1. Persist/update a primary entity.
  2. Write a row to a secondary "audit" or "history" table recording the
     change (versioned by an `(id, version)` composite key).
  3. Update a piece of state on the primary entity — or a related
     workflow/state-machine record — based on the outcome of steps 1–2.
- The audit-table insert has always been able to fail under concurrency: two
  requests touching the same primary row at close to the same time can
  compute the same next `version` number, tripping a unique constraint on
  `(id, version)`.
- This has been true for over a decade, going back to a Hibernate 3.x-era
  version of the stack. The failure is caught locally, right at the insert
  call site, logged, and the request continues. Nobody treated it as a real
  problem, because nothing downstream ever appeared to be affected by it.
- The application recently upgraded its persistence stack from Hibernate 3.6
  to a Hibernate 5.6 / Jakarta Persistence–based configuration (Spring
  correspondingly modernized), as part of a broader Jakarta EE migration.

---

## The Symptom

After the upgrade, step 3 above — the state update that's supposed to
happen *after* the audit insert — started silently failing to persist on a
large fraction of requests. Specifically:

- No exception reached any calling code, at any layer.
- No `WARN`/`ERROR` log line appeared anywhere in the request's trace.
- The method's own logging showed every step executing, including the log
  line only reachable *after* the state-update code runs.
- Instrumenting the transaction directly (see below) showed it reporting a
  clean, successful **commit** — not a rollback.
- The failure rate was inconsistent: sometimes 100% across many consecutive
  attempts on the same record, sometimes clearing after an unpredictable
  delay with no code or environment change in between — the signature of a
  timing-dependent race, not a deterministic logic bug.

This combination — clean commit, zero exceptions, code demonstrably
executing the "missing" branch — ruled out most of the usual suspects
quickly: no rollback exception was being thrown and swallowed (a real
rollback would propagate synchronously and prevent the code that runs
*after* it from executing, which it didn't); the relevant history/async
tables agreed with the live/runtime tables, ruling out replication or
async-job lag; the two subsystems involved shared a single Spring-managed
transaction manager, ruling out a split-transaction problem.

---

## Root Cause

The audit-table exception has always been caught and logged at its origin —
that part of the code never changed. What changed is what that exception
does *inside* Hibernate before the `catch` block ever sees it.

As of Hibernate 5.2 / Spring 4.3.2, Hibernate's exception-conversion layer
(`ExceptionConverterImpl`) wraps native Hibernate exceptions — including
constraint violations — into standard JPA `PersistenceException`s, and as
part of that conversion, calls `session.markForRollbackOnly()` on the
current Hibernate session:

> *"As of Spring 4.3.2 and Hibernate 5.2, it also converts standard JPA
> `PersistenceException` instances."*
> — Spring Framework, `HibernateExceptionTranslator` Javadoc
> `docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/orm/hibernate5/HibernateExceptionTranslator.html`

The actual call chain (`ExceptionConverterImpl.convert(...)` →
`handlePersistenceException(...)` → `session.markForRollbackOnly()`) is
visible directly in Hibernate's own source:
`github.com/hibernate/hibernate-orm/blob/main/hibernate-core/src/main/java/org/hibernate/internal/ExceptionConverterImpl.java`

— and the same call shape shows up in an unrelated, independently reported
production incident on a Red Hat/JBoss developer forum, confirming this
isn't a one-off:
`developer.jboss.org/thread/279303`

This is not new or accidental behavior — it's Hibernate enforcing something
JPA has always specified in principle: once a persistence context throws a
`PersistenceException`, it should be considered unreliable. The official
guidance has said as much for a long time:

> *"If the Session throws an exception... immediately rollback the database
> transaction, call `Session.close()` and discard the Session instance...
> No exception thrown by Hibernate can be treated as recoverable."*
> — Hibernate Core Reference Guide, §11.2.3 "Exception handling"

Catching the exception locally and continuing to use the same session —
exactly what the decade-old audit-insert error handler does — was always
technically unsupported. It simply wasn't *enforced* by anything in the
older stack, so it worked anyway, for years.

Crucially, marking a session `rollbackOnly` internally inside Hibernate is
**invisible to Spring's own transaction bookkeeping**. Spring's
`HibernateTransactionManager` decides whether to commit or roll back based
on whether an exception escaped the `@Transactional`-proxied method
boundary — nothing more:

> *"[doCommit] does not need to check the 'new transaction' flag or the
> rollback-only flag; this will already have been handled before."*
> — Spring Framework, `HibernateTransactionManager` Javadoc
> `docs.spring.io/spring-framework/docs/6.2.18/javadoc-api/org/springframework/orm/hibernate5/HibernateTransactionManager.html`

Since the audit exception is caught before it ever reaches the method
boundary, Spring never sees anything wrong, and commits. The commit is
completely real — it's just a commit of an empty changeset for everything
that was staged in the persistence context after the point of failure,
because Hibernate silently stopped trusting that persistence context the
moment `markForRollbackOnly()` fired.

### Simplified before/after

```
BEFORE (pre-upgrade stack)
  step 1: persist primary entity        → flushes fine
  step 2: audit insert                  → constraint violation, caught, logged
  (session keeps flushing normally)
  step 3: state update                  → flushes fine
  no exception escaped the method       → Spring commits
  RESULT: audit row lost. Everything else persists (visible, tolerated bug)

AFTER (post-upgrade stack)
  step 1: persist primary entity        → flushes fine
  step 2: audit insert                  → constraint violation, caught, logged
    → Hibernate's exception converter marks the session rollback-only
      internally (sourced above)
  (session no longer reliably flushes further staged changes)
  step 3: state update                  → never reaches the database
  no exception escaped the method       → Spring commits anyway
  RESULT: audit row lost (as always) AND the state update silently lost too
```

---

## Diagnosis Approach

A few techniques were essential in narrowing this down, roughly in the
order they were useful:

1. **Confirm the actual transaction outcome — don't trust the absence of
   exceptions.** Register a `TransactionSynchronization` at the top of the
   suspect method and log `afterCompletion(status)`. This is the only
   reliable way to know whether Spring believes it committed or rolled back,
   independent of anything the application code logs.
2. **Compare application-level state against the datastore's own ground
   truth directly**, not just through whatever ORM/history logging layer
   you normally rely on. Checking a workflow engine's live runtime table
   against its own history/audit table (they agreed, ruling out async
   replication lag) was more informative than any application log.
3. **Rule out theories by falsifying them with a specific, checkable fact**,
   not by how plausible they sound. Several intuitive theories (a swallowed
   rollback exception, split transaction managers, async job lag, a logic
   gate silently evaluating false) were each individually disproven by
   direct evidence, one at a time, rather than argued away.
4. **Get bind-variable-level SQL logging working end-to-end, and know which
   persistence layer actually owns the write you're chasing.** In a mixed
   environment (e.g. Hibernate plus a separately-persisted workflow/state
   engine sharing one transaction), turning up Hibernate's SQL logging alone
   won't show you a write that never went through Hibernate in the first
   place.
5. **When a caught exception is "known and harmless," re-verify that after
   any major dependency upgrade.** The exception itself didn't change; the
   safety of ignoring it did.

---

## The Fix

The specific fix here didn't require any application code change. The
constraint being violated — a legacy `(id, version)` uniqueness check on the
audit/history table — turned out to protect nothing: the audit table
already had a genuine creation-timestamp discriminator, making id/version
reuse across audit rows harmless in practice. The constraint was dropped.
With nothing left to violate, the exception stops firing, the session is
never marked rollback-only, and the downstream state update persists
normally.

A follow-up, lower-urgency hardening item was also flagged: the pattern of
catching a persistence exception locally and continuing to use the same
session inside a larger `@Transactional` method is now known to be fragile
under the current stack. Isolating that kind of "fire and forget" audit
write in its own `REQUIRES_NEW` transaction — or explicitly checking
rollback-only status after any caught persistence exception — would prevent
a *different* exception, in the future, from silently reproducing the same
class of failure.

---

## Takeaways

- A caught exception that "has always been harmless" is a statement about
  the code *and* the framework version underneath it. Major ORM upgrades
  can quietly revoke that safety without changing a single line of
  application code.
- "The transaction committed successfully" and "everything that should have
  been written was written" are not the same claim. Verifying the first
  doesn't verify the second when a persistence context has multiple
  independent writers contributing to it.
- Two frameworks (a transaction manager, an ORM session) can each behave
  correctly by their own rules and still combine to produce a completely
  silent failure, because neither one's contract requires it to check the
  other's internal state.
