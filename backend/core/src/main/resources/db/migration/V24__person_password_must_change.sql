-- F-8: first-login password change for persons born with the bootstrap password.
-- The IdP (credential owner) flips the flag to false on successful change; core sets it
-- true at every provisioning site that uses FSP_PERSON_PASSWORD. SQL seeds (system/demo
-- personas) keep the default false — their passwords are environment constants, not secrets
-- handed to a user at first sign-in.

ALTER TABLE persons ADD COLUMN password_must_change BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX persons_must_change_idx ON persons (password_must_change) WHERE password_must_change;
