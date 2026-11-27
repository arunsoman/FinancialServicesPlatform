-- FSP plugin databases + machine-scoped roles. Runs once on a fresh postgres volume.
-- App databases: fsp (core/idp/bff) via POSTGRES_DB; plugin DBs created here.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fsp_plugin_kyc') THEN
    CREATE ROLE fsp_plugin_kyc LOGIN PASSWORD 'fsp_plugin_kyc';
  END IF;
END $$;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fsp_plugin_inventory') THEN
    CREATE ROLE fsp_plugin_inventory LOGIN PASSWORD 'fsp_plugin_inventory';
  END IF;
END $$;
SELECT 'CREATE DATABASE fsp_plugin_kyc OWNER fsp_plugin_kyc'
  WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'fsp_plugin_kyc')\gexec
SELECT 'CREATE DATABASE fsp_plugin_inventory OWNER fsp_plugin_inventory'
  WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'fsp_plugin_inventory')\gexec

-- Chapter 13 Stage 0: psp-service (:8085) and emi-service (:8086) databases.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fsp_psp') THEN
    CREATE ROLE fsp_psp LOGIN PASSWORD 'fsp_psp';
  END IF;
END $$;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fsp_emi') THEN
    CREATE ROLE fsp_emi LOGIN PASSWORD 'fsp_emi';
  END IF;
END $$;
SELECT 'CREATE DATABASE fsp_psp OWNER fsp_psp'
  WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'fsp_psp')\gexec
SELECT 'CREATE DATABASE fsp_emi OWNER fsp_emi'
  WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'fsp_emi')\gexec
