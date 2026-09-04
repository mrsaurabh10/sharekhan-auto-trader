-- Fix/quarantine simulator PnL rows where actual_entry_price is clearly the
-- spot price instead of the traded option/instrument entry price.
--
-- The database does not contain the correct option entry price for these rows.
-- By default this backs up the affected rows and sets pnl = NULL so analytics
-- stop showing fake oversized losses. If you know the actual option entry price,
-- add it to manual_entry_prices and the script will recalculate PnL.

SET AUTOCOMMIT OFF;

CREATE TABLE IF NOT EXISTS triggered_trade_setups_spot_entry_pnl_fix_backup (
    id BIGINT PRIMARY KEY,
    old_actual_entry_price DOUBLE PRECISION,
    old_pnl DOUBLE PRECISION,
    corrected_actual_entry_price DOUBLE PRECISION,
    corrected_pnl DOUBLE PRECISION,
    entry_price DOUBLE PRECISION,
    exit_price DOUBLE PRECISION,
    quantity BIGINT,
    exit_reason CHARACTER VARYING,
    backed_up_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE LOCAL TEMPORARY TABLE manual_entry_prices (
    id BIGINT PRIMARY KEY,
    actual_entry_price DOUBLE PRECISION
) NOT PERSISTENT;

-- Optional manual corrections:
-- INSERT INTO manual_entry_prices (id, actual_entry_price) VALUES
--     (3332, 0.0); -- MFSL: replace 0.0 with the actual option entry price

MERGE INTO triggered_trade_setups_spot_entry_pnl_fix_backup backup
USING (
    SELECT
        t.id,
        t.actual_entry_price AS old_actual_entry_price,
        t.pnl AS old_pnl,
        m.actual_entry_price AS corrected_actual_entry_price,
        CASE
            WHEN m.actual_entry_price IS NULL THEN NULL
            ELSE ROUND((t.exit_price - m.actual_entry_price) * t.quantity, 2)
        END AS corrected_pnl,
        t.entry_price,
        t.exit_price,
        t.quantity,
        t.exit_reason
    FROM triggered_trade_setups t
    JOIN broker_credentials b ON b.id = t.broker_credentials_id
    LEFT JOIN manual_entry_prices m ON m.id = t.id
    WHERE UPPER(b.broker_name) = 'SIMULATOR'
      AND t.status = 'EXITED_SUCCESS'
      AND t.pnl IS NOT NULL
      AND t.entry_price IS NOT NULL
      AND t.actual_entry_price IS NOT NULL
      AND t.exit_price IS NOT NULL
      AND t.quantity IS NOT NULL
      AND t.quantity > 0
      AND (
          t.use_spot_for_entry = TRUE
          OR t.use_spot_for_sl = TRUE
          OR t.use_spot_for_target = TRUE
          OR t.use_spot_price = TRUE
      )
      AND t.entry_price > 100
      AND t.actual_entry_price > 100
      AND ABS(t.actual_entry_price - t.entry_price) / t.entry_price <= 0.20
      AND t.exit_price < t.actual_entry_price / 10
) src
ON backup.id = src.id
WHEN NOT MATCHED THEN
    INSERT (
        id,
        old_actual_entry_price,
        old_pnl,
        corrected_actual_entry_price,
        corrected_pnl,
        entry_price,
        exit_price,
        quantity,
        exit_reason
    )
    VALUES (
        src.id,
        src.old_actual_entry_price,
        src.old_pnl,
        src.corrected_actual_entry_price,
        src.corrected_pnl,
        src.entry_price,
        src.exit_price,
        src.quantity,
        src.exit_reason
    );

SELECT
    t.id,
    t.symbol,
    t.entry_price AS spot_entry_price,
    t.actual_entry_price AS spot_like_actual_entry_price,
    m.actual_entry_price AS manual_actual_entry_price,
    t.exit_price,
    t.quantity,
    t.pnl AS old_pnl,
    CASE
        WHEN m.actual_entry_price IS NULL THEN NULL
        ELSE ROUND((t.exit_price - m.actual_entry_price) * t.quantity, 2)
    END AS corrected_pnl,
    t.exit_reason,
    t.exited_at
FROM triggered_trade_setups t
JOIN broker_credentials b ON b.id = t.broker_credentials_id
LEFT JOIN manual_entry_prices m ON m.id = t.id
WHERE UPPER(b.broker_name) = 'SIMULATOR'
  AND t.status = 'EXITED_SUCCESS'
  AND t.pnl IS NOT NULL
  AND t.entry_price IS NOT NULL
  AND t.actual_entry_price IS NOT NULL
  AND t.exit_price IS NOT NULL
  AND t.quantity IS NOT NULL
  AND t.quantity > 0
  AND (
      t.use_spot_for_entry = TRUE
      OR t.use_spot_for_sl = TRUE
      OR t.use_spot_for_target = TRUE
      OR t.use_spot_price = TRUE
  )
  AND t.entry_price > 100
  AND t.actual_entry_price > 100
  AND ABS(t.actual_entry_price - t.entry_price) / t.entry_price <= 0.20
  AND t.exit_price < t.actual_entry_price / 10
ORDER BY t.id;

UPDATE triggered_trade_setups t
SET
    actual_entry_price = (
        SELECT COALESCE(m.actual_entry_price, t.actual_entry_price)
        FROM manual_entry_prices m
        WHERE m.id = t.id
    ),
    pnl = (
        SELECT ROUND((t.exit_price - m.actual_entry_price) * t.quantity, 2)
        FROM manual_entry_prices m
        WHERE m.id = t.id
    )
WHERE t.id IN (
    SELECT candidate.id
    FROM triggered_trade_setups candidate
    JOIN broker_credentials b ON b.id = candidate.broker_credentials_id
    WHERE UPPER(b.broker_name) = 'SIMULATOR'
      AND candidate.status = 'EXITED_SUCCESS'
      AND candidate.pnl IS NOT NULL
      AND candidate.entry_price IS NOT NULL
      AND candidate.actual_entry_price IS NOT NULL
      AND candidate.exit_price IS NOT NULL
      AND candidate.quantity IS NOT NULL
      AND candidate.quantity > 0
      AND (
          candidate.use_spot_for_entry = TRUE
          OR candidate.use_spot_for_sl = TRUE
          OR candidate.use_spot_for_target = TRUE
          OR candidate.use_spot_price = TRUE
      )
      AND candidate.entry_price > 100
      AND candidate.actual_entry_price > 100
      AND ABS(candidate.actual_entry_price - candidate.entry_price) / candidate.entry_price <= 0.20
      AND candidate.exit_price < candidate.actual_entry_price / 10
)
AND EXISTS (
    SELECT 1
    FROM manual_entry_prices m
    WHERE m.id = t.id
);

UPDATE triggered_trade_setups t
SET pnl = NULL
WHERE t.id IN (
    SELECT candidate.id
    FROM triggered_trade_setups candidate
    JOIN broker_credentials b ON b.id = candidate.broker_credentials_id
    LEFT JOIN manual_entry_prices m ON m.id = candidate.id
    WHERE m.id IS NULL
      AND UPPER(b.broker_name) = 'SIMULATOR'
      AND candidate.status = 'EXITED_SUCCESS'
      AND candidate.pnl IS NOT NULL
      AND candidate.entry_price IS NOT NULL
      AND candidate.actual_entry_price IS NOT NULL
      AND candidate.exit_price IS NOT NULL
      AND candidate.quantity IS NOT NULL
      AND candidate.quantity > 0
      AND (
          candidate.use_spot_for_entry = TRUE
          OR candidate.use_spot_for_sl = TRUE
          OR candidate.use_spot_for_target = TRUE
          OR candidate.use_spot_price = TRUE
      )
      AND candidate.entry_price > 100
      AND candidate.actual_entry_price > 100
      AND ABS(candidate.actual_entry_price - candidate.entry_price) / candidate.entry_price <= 0.20
      AND candidate.exit_price < candidate.actual_entry_price / 10
);

SELECT COUNT(*) AS remaining_oversized_spot_entry_pnl_rows
FROM triggered_trade_setups t
JOIN broker_credentials b ON b.id = t.broker_credentials_id
WHERE UPPER(b.broker_name) = 'SIMULATOR'
  AND t.status = 'EXITED_SUCCESS'
  AND t.pnl IS NOT NULL
  AND t.entry_price IS NOT NULL
  AND t.actual_entry_price IS NOT NULL
  AND t.exit_price IS NOT NULL
  AND t.quantity IS NOT NULL
  AND t.quantity > 0
  AND (
      t.use_spot_for_entry = TRUE
      OR t.use_spot_for_sl = TRUE
      OR t.use_spot_for_target = TRUE
      OR t.use_spot_price = TRUE
  )
  AND t.entry_price > 100
  AND t.actual_entry_price > 100
  AND ABS(t.actual_entry_price - t.entry_price) / t.entry_price <= 0.20
  AND t.exit_price < t.actual_entry_price / 10;

COMMIT;
