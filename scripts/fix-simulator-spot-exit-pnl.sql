-- Fix/quarantine simulator PnL rows where exit_price is clearly the spot price
-- instead of the traded option/instrument exit price.
--
-- Why this is separate from fix-simulator-spot-pnl.sql:
-- that script can recalculate PnL when exit_price is correct and entry_price is
-- wrong. This script handles rows where exit_price itself is wrong. The DB has
-- no reliable actual exit option price for those rows, so by default it sets PnL
-- to NULL to remove bogus oversized values from analytics. If you know the
-- actual option exit price for a row, add it to manual_exit_prices below and the
-- script will calculate PnL from that value instead.
--
-- Run against the active H2 DB, for example:
-- java -cp ~/.m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar \
--   org.h2.tools.RunScript \
--   -url "jdbc:h2:file:./data/scriptdbusertest" \
--   -user sa \
--   -password "" \
--   -script scripts/fix-simulator-spot-exit-pnl.sql

SET AUTOCOMMIT OFF;

CREATE TABLE IF NOT EXISTS triggered_trade_setups_spot_exit_pnl_fix_backup (
    id BIGINT PRIMARY KEY,
    old_exit_price DOUBLE PRECISION,
    old_pnl DOUBLE PRECISION,
    corrected_exit_price DOUBLE PRECISION,
    corrected_pnl DOUBLE PRECISION,
    entry_price DOUBLE PRECISION,
    actual_entry_price DOUBLE PRECISION,
    quantity BIGINT,
    exit_reason CHARACTER VARYING,
    backed_up_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE LOCAL TEMPORARY TABLE manual_exit_prices (
    id BIGINT PRIMARY KEY,
    actual_exit_price DOUBLE PRECISION
) NOT PERSISTENT;

-- Optional manual corrections:
-- INSERT INTO manual_exit_prices (id, actual_exit_price) VALUES
--     (3306, 0.0); -- TECHM: replace 0.0 with the actual option exit price

MERGE INTO triggered_trade_setups_spot_exit_pnl_fix_backup backup
USING (
    SELECT
        t.id,
        t.exit_price AS old_exit_price,
        t.pnl AS old_pnl,
        m.actual_exit_price AS corrected_exit_price,
        CASE
            WHEN m.actual_exit_price IS NULL THEN NULL
            ELSE ROUND((m.actual_exit_price - t.actual_entry_price) * t.quantity, 2)
        END AS corrected_pnl,
        t.entry_price,
        t.actual_entry_price,
        t.quantity,
        t.exit_reason
    FROM triggered_trade_setups t
    JOIN broker_credentials b ON b.id = t.broker_credentials_id
    LEFT JOIN manual_exit_prices m ON m.id = t.id
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
      -- Option/future exit prices should be in the same rough range as actual_entry_price.
      -- When exit_price is also close to entry_price, it is almost certainly spot.
      AND t.actual_entry_price > 0
      AND t.entry_price > t.actual_entry_price * 10
      AND t.exit_price > t.actual_entry_price * 10
) src
ON backup.id = src.id
WHEN NOT MATCHED THEN
    INSERT (
        id,
        old_exit_price,
        old_pnl,
        corrected_exit_price,
        corrected_pnl,
        entry_price,
        actual_entry_price,
        quantity,
        exit_reason
    )
    VALUES (
        src.id,
        src.old_exit_price,
        src.old_pnl,
        src.corrected_exit_price,
        src.corrected_pnl,
        src.entry_price,
        src.actual_entry_price,
        src.quantity,
        src.exit_reason
    );

-- Preview affected rows. Rows without manual_exit_prices will get pnl = NULL.
SELECT
    t.id,
    t.symbol,
    t.entry_price AS spot_entry_price,
    t.actual_entry_price,
    t.exit_price AS spot_like_exit_price,
    m.actual_exit_price AS manual_actual_exit_price,
    t.quantity,
    t.pnl AS old_pnl,
    CASE
        WHEN m.actual_exit_price IS NULL THEN NULL
        ELSE ROUND((m.actual_exit_price - t.actual_entry_price) * t.quantity, 2)
    END AS corrected_pnl,
    t.exit_reason,
    t.exited_at
FROM triggered_trade_setups t
JOIN broker_credentials b ON b.id = t.broker_credentials_id
LEFT JOIN manual_exit_prices m ON m.id = t.id
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
  AND t.actual_entry_price > 0
  AND t.entry_price > t.actual_entry_price * 10
  AND t.exit_price > t.actual_entry_price * 10
ORDER BY t.id;

UPDATE triggered_trade_setups t
SET
    exit_price = (
        SELECT COALESCE(m.actual_exit_price, t.exit_price)
        FROM manual_exit_prices m
        WHERE m.id = t.id
    ),
    pnl = (
        SELECT ROUND((m.actual_exit_price - t.actual_entry_price) * t.quantity, 2)
        FROM manual_exit_prices m
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
      AND candidate.actual_entry_price > 0
      AND candidate.entry_price > candidate.actual_entry_price * 10
      AND candidate.exit_price > candidate.actual_entry_price * 10
)
AND EXISTS (
    SELECT 1
    FROM manual_exit_prices m
    WHERE m.id = t.id
);

UPDATE triggered_trade_setups t
SET pnl = NULL
WHERE t.id IN (
    SELECT candidate.id
    FROM triggered_trade_setups candidate
    JOIN broker_credentials b ON b.id = candidate.broker_credentials_id
    LEFT JOIN manual_exit_prices m ON m.id = candidate.id
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
      AND candidate.actual_entry_price > 0
      AND candidate.entry_price > candidate.actual_entry_price * 10
      AND candidate.exit_price > candidate.actual_entry_price * 10
);

-- Should return 0 after the update unless you manually left rows with PnL set.
SELECT COUNT(*) AS remaining_oversized_spot_exit_pnl_rows
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
  AND t.actual_entry_price > 0
  AND t.entry_price > t.actual_entry_price * 10
  AND t.exit_price > t.actual_entry_price * 10;

COMMIT;
