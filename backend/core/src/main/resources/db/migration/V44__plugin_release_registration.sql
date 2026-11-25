-- V44: plugins ship as code (AGENTS.md §2.1 exception, docs/plugin-registration.md).
-- A plugin version can now be registered by a RELEASE: the manifest was reviewed in the pull
-- request and the deploy applies it, with the people behind that review recorded in `provenance`.
-- Such a row has no FSP person as maker (registered_by NULL) — never a machine either.
-- Suspension (four-eyes, from Grafana) is recorded so a later deploy never silently lifts it.

ALTER TABLE plugin_types ALTER COLUMN registered_by DROP NOT NULL;

ALTER TABLE plugin_types
    ADD COLUMN registered_via VARCHAR(16) NOT NULL DEFAULT 'APPROVAL',  -- APPROVAL | RELEASE
    ADD COLUMN release_ref    VARCHAR(128),                             -- release name that registered it
    ADD COLUMN provenance     JSONB,                                    -- commit, PR, author, reviewers, deploy approvers
    ADD COLUMN suspended_by   UUID,                                     -- checker of the PLUGIN_SUSPEND approval
    ADD COLUMN suspended_at   TIMESTAMPTZ,
    ADD COLUMN suspended_reason VARCHAR(16);                            -- PERSON (four-eyes) | RELEASE (plugin left the repo)

ALTER TABLE plugin_types ADD CONSTRAINT plugin_type_registered_via CHECK (registered_via IN ('APPROVAL','RELEASE'));
ALTER TABLE plugin_types ADD CONSTRAINT plugin_type_suspended_reason CHECK (suspended_reason IS NULL OR suspended_reason IN ('PERSON','RELEASE'));
-- a person-registered row always names its maker; a release row always names its release
ALTER TABLE plugin_types ADD CONSTRAINT plugin_type_maker_shape CHECK (
    (registered_via = 'APPROVAL' AND registered_by IS NOT NULL) OR
    (registered_via = 'RELEASE'  AND release_ref IS NOT NULL AND provenance IS NOT NULL));
