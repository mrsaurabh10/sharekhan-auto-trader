-- Market data only. No account, credential, order or position tables.
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout='60s';
SELECT json_build_object('type','snapshot','schema','NSE_DAILY_SNAPSHOT_V1',
 'as_of',:'as_of','exported_at',now(),'provider','DHAN','exchange_segment','NSE_EQ',
 'universe','NIFTY 200','membership_policy','LATEST_SNAPSHOT_NOT_POINT_IN_TIME',
 'snapshot_date',(SELECT max(snapshot_date) FROM stock_index_constituents WHERE index_name='NIFTY 200'));
SELECT json_build_object('type','member','symbol',symbol,'isin',isin,
 'snapshot_date',snapshot_date,'source_url',source_url,'source_sha256',source_sha256)
FROM stock_index_constituents WHERE index_name='NIFTY 200'
 AND snapshot_date=(SELECT max(snapshot_date) FROM stock_index_constituents WHERE index_name='NIFTY 200') ORDER BY symbol;
SELECT json_build_object('type','candle','symbol',c.symbol,'security_id',c.security_id,
 'date',c.candle_date,'epoch',c.candle_epoch,'open',c.open_price,'high',c.high_price,
 'low',c.low_price,'close',c.close_price,'volume',c.volume,'tick_size',c.tick_size,
 'captured_at',c.captured_at,'import_id',c.import_id,'adjustment_status',c.adjustment_status)
FROM stock_daily_candles c
WHERE c.provider='DHAN' AND c.exchange_segment='NSE_EQ' AND c.candle_date<=CAST(:'as_of' AS date)
 AND EXISTS (SELECT 1 FROM stock_index_constituents m WHERE m.index_name='NIFTY 200'
  AND m.snapshot_date=(SELECT max(snapshot_date) FROM stock_index_constituents WHERE index_name='NIFTY 200') AND m.symbol=c.symbol)
ORDER BY c.symbol,c.candle_date,c.security_id;
SELECT json_build_object('type','end','members',
 (SELECT count(*) FROM stock_index_constituents WHERE index_name='NIFTY 200' AND snapshot_date=(SELECT max(snapshot_date) FROM stock_index_constituents WHERE index_name='NIFTY 200')),
 'candles',(SELECT count(*) FROM stock_daily_candles c WHERE c.provider='DHAN' AND c.exchange_segment='NSE_EQ' AND c.candle_date<=CAST(:'as_of' AS date)
 AND EXISTS (SELECT 1 FROM stock_index_constituents m WHERE m.index_name='NIFTY 200' AND m.snapshot_date=(SELECT max(snapshot_date) FROM stock_index_constituents WHERE index_name='NIFTY 200') AND m.symbol=c.symbol)));
COMMIT;
