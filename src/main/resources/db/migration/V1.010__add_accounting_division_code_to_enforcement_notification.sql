-- CIMD-4640: the accounting division code of the defendant's enforcement area (reference data, by the defendant's
-- postcode, else by the hearing court's LJA), resolved when the GOB data is ready and published with it to hearing-nows.
-- NULL when reference data has no enforcement area for either, and in rows marked ready before this change. A reference
-- data code, not personal data.

ALTER TABLE enforcement_notification ADD COLUMN accounting_division_code integer;
