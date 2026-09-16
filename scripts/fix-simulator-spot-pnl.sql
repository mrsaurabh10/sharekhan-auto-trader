-- Fix historical simulator PnL rows that were calculated from the spot trigger
-- entry_price instead of the executed instrument actual_entry_price.
--
-- Run against the active H2 DB, for example:
-- java -cp ~/.m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar \
--   org.h2.tools.RunScript \
--   -url "jdbc:h2:file:./data/scriptdbusertest" \
--   -user sa \
--   -password "" \
--   -script scripts/fix-simulator-spot-pnl.sql

SET AUTOCOMMIT OFF;

CREATE TABLE IF NOT EXISTS triggered_trade_setups_pnl_fix_backup (
    id BIGINT PRIMARY KEY,
    old_pnl DOUBLE PRECISION,
    corrected_pnl DOUBLE PRECISION,
    entry_price DOUBLE PRECISION,
    actual_entry_price DOUBLE PRECISION,
    exit_price DOUBLE PRECISION,
    quantity BIGINT,
    broker_credentials_id BIGINT,
    backed_up_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

MERGE INTO triggered_trade_setups_pnl_fix_backup backup
USING (
    SELECT
        t.id,
        t.pnl AS old_pnl,
        ROUND((t.exit_price - t.actual_entry_price) * t.quantity, 2) AS corrected_pnl,
        t.entry_price,
        t.actual_entry_price,
        t.exit_price,
        t.quantity,
        t.broker_credentials_id
    FROM triggered_trade_setups t
    JOIN broker_credentials b ON b.id = t.broker_credentials_id
    WHERE UPPER(b.broker_name) = 'SIMULATOR'
      AND t.status = 'EXITED_SUCCESS'
      AND t.pnl IS NOT NULL
      AND t.exit_price IS NOT NULL
      AND t.actual_entry_price IS NOT NULL
      AND t.quantity IS NOT NULL
      AND t.quantity > 0
      AND (
          t.use_spot_for_entry = TRUE
          OR (t.use_spot_for_entry IS NULL AND t.use_spot_price = TRUE)
      )
      AND ABS(t.pnl - ROUND((t.exit_price - t.actual_entry_price) * t.quantity, 2)) > 0.01
) src
ON backup.id = src.id
WHEN NOT MATCHED THEN
    INSERT (
        id,
        old_pnl,
        corrected_pnl,
        entry_price,
        actual_entry_price,
        exit_price,
        quantity,
        broker_credentials_id
    )
    VALUES (
        src.id,
        src.old_pnl,
        src.corrected_pnl,
        src.entry_price,
        src.actual_entry_price,
        src.exit_price,
        src.quantity,
        src.broker_credentials_id
    );

-- Preview the rows being corrected. Keep this result with your run notes.
SELECT
    t.id,
    t.symbol,
    t.entry_price,
    t.actual_entry_price,
    t.exit_price,
    t.quantity,
    t.pnl AS old_pnl,
    ROUND((t.exit_price - t.actual_entry_price) * t.quantity, 2) AS corrected_pnl,
    ROUND((t.exit_price - t.entry_price) * t.quantity, 2) AS spot_based_pnl,
    t.exit_reason,
    t.exited_at
FROM triggered_trade_setups t
JOIN broker_credentials b ON b.id = t.broker_credentials_id
WHERE UPPER(b.broker_name) = 'SIMULATOR'
  AND t.status = 'EXITED_SUCCESS'
  AND t.pnl IS NOT NULL
  AND t.exit_price IS NOT NULL
  AND t.actual_entry_price IS NOT NULL
  AND t.quantity IS NOT NULL
  AND t.quantity > 0
  AND (
      t.use_spot_for_entry = TRUE
      OR (t.use_spot_for_entry IS NULL AND t.use_spot_price = TRUE)
  )
  AND ABS(t.pnl - ROUND((t.exit_price - t.actual_entry_price) * t.quantity, 2)) > 0.01
ORDER BY t.id;

UPDATE triggered_trade_setups
SET pnl = ROUND((exit_price - actual_entry_price) * quantity, 2)
WHERE id IN (
    SELECT t.id
    FROM triggered_trade_setups t
    JOIN broker_credentials b ON b.id = t.broker_credentials_id
    WHERE UPPER(b.broker_name) = 'SIMULATOR'
      AND t.status = 'EXITED_SUCCESS'
      AND t.pnl IS NOT NULL
      AND t.exit_price IS NOT NULL
      AND t.actual_entry_price IS NOT NULL
      AND t.quantity IS NOT NULL
      AND t.quantity > 0
      AND (
          t.use_spot_for_entry = TRUE
          OR (t.use_spot_for_entry IS NULL AND t.use_spot_price = TRUE)
      )
      AND ABS(t.pnl - ROUND((t.exit_price - t.actual_entry_price) * t.quantity, 2)) > 0.01
);

-- Should return 0 after the update.
SELECT COUNT(*) AS remaining_bad_simulator_spot_pnl_rows
FROM triggered_trade_setups t
JOIN broker_credentials b ON b.id = t.broker_credentials_id
WHERE UPPER(b.broker_name) = 'SIMULATOR'
  AND t.status = 'EXITED_SUCCESS'
  AND t.pnl IS NOT NULL
  AND t.exit_price IS NOT NULL
  AND t.actual_entry_price IS NOT NULL
  AND t.quantity IS NOT NULL
  AND t.quantity > 0
  AND (
      t.use_spot_for_entry = TRUE
      OR (t.use_spot_for_entry IS NULL AND t.use_spot_price = TRUE)
  )
  AND ABS(t.pnl - ROUND((t.exit_price - t.actual_entry_price) * t.quantity, 2)) > 0.01;

COMMIT;
