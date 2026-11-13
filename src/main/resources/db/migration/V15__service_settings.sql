-- 4.2.0 (spec 009, data-model §2): service-wide settings shared by every instance. 'acl_security' holds the
-- access-control mode of the most recent start ('enabled' | 'disabled'); a missing row means no stored mode yet, so
-- the first start after the upgrade needs no FV_ACL_MODE_CHANGE_CONFIRMED.
CREATE TABLE IF NOT EXISTS fhir_validator_schema.service_settings (
    name       VARCHAR(64)  PRIMARY KEY,
    value      VARCHAR(256) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by VARCHAR(128) NULL,
    CONSTRAINT service_settings_acl_chk CHECK (name <> 'acl_security' OR value IN ('enabled', 'disabled'))
);
