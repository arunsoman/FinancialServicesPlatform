-- ROLE MENU GRANTS + ORG UNITS (Role Management: Add Role form).
--
-- 1. branches / departments — org-scoped master data a role is anchored to. Minimal for now:
--    read by the Add Role form; their own CRUD screens (Role Management → Branch / Department)
--    come later. Never hard-deleted (ARCHIVED), optimistic-locked (AGENTS §2d).
-- 2. roles gain a description and an optional branch + department.
-- 3. role_menu_grants — the Permissions Matrix: one row per (published submenu × action) a role
--    holds, keyed by the PUBLISHED layout ids of the portal the role's holders sign in to.
--    ACTIVE = the submenu shows in that portal's sidebar; CREATE/VIEW/UPDATE/DELETE/AUTHORIZE
--    are the action cells the submenu's screen checks. Platform-scope roles and every org's two
--    anchor roles (TENANT_ADMIN / TENANT_CHECKER) hold the whole menu implicitly (no rows).

CREATE TABLE branches (
    id          UUID PRIMARY KEY,
    tenant_id   UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT branches_status_check CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT branches_tenant_code_uq UNIQUE (tenant_id, code),
    CONSTRAINT branches_tenant_name_uq UNIQUE (tenant_id, name)
);
CREATE INDEX ix_branches_tenant_status_name ON branches (tenant_id, status, name);

CREATE TABLE departments (
    id          UUID PRIMARY KEY,
    tenant_id   UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT departments_status_check CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT departments_tenant_code_uq UNIQUE (tenant_id, code),
    CONSTRAINT departments_tenant_name_uq UNIQUE (tenant_id, name)
);
CREATE INDEX ix_departments_tenant_status_name ON departments (tenant_id, status, name);

ALTER TABLE roles ADD COLUMN description   VARCHAR(500) NULL;
ALTER TABLE roles ADD COLUMN branch_id     UUID NULL REFERENCES branches(id);
ALTER TABLE roles ADD COLUMN department_id UUID NULL REFERENCES departments(id);

CREATE TABLE role_menu_grants (
    role_id    UUID        NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    portal     VARCHAR(40) NOT NULL,
    menu_id    VARCHAR(48) NOT NULL,
    screen_id  VARCHAR(48) NOT NULL,
    action     VARCHAR(16) NOT NULL,
    PRIMARY KEY (role_id, portal, menu_id, screen_id, action),
    CONSTRAINT role_menu_grants_action_check
        CHECK (action IN ('CREATE', 'VIEW', 'UPDATE', 'DELETE', 'AUTHORIZE', 'ACTIVE'))
);
