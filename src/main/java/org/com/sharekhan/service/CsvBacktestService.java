package org.com.sharekhan.service;

import org.com.sharekhan.backtest.csv.*;
import org.com.sharekhan.config.SupertrendStrategyProperties;
import org.com.sharekhan.entity.CsvBacktestDatasetEntity;
import org.com.sharekhan.repository.CsvBacktestDatasetRepository;
import org.com.sharekhan.strategy.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class CsvBacktestService {
    private final CsvBacktestDatasetRepository datasets;
    private final JdbcTemplate jdbc;
    private final SupertrendCsvBacktest engine;
    private final SupertrendStrategyProperties properties;
    public CsvBacktestService(CsvBacktestDatasetRepository datasets,DataSource dataSource,IndicatorService indicators,
                              SupertrendSignalRules rules,SupertrendStrategyProperties properties) {
        this.datasets=datasets;this.jdbc=new JdbcTemplate(dataSource);this.engine=new SupertrendCsvBacktest(indicators,rules);this.properties=properties;
    }
    @Transactional
    public synchronized CsvBacktestDatasetEntity importCsv(InputStream input,String filename,String symbol,int inputMinutes) throws IOException {
        if (!Set.of("NIFTY","BANKNIFTY").contains(symbol)) throw new IllegalArgumentException("symbol must be NIFTY or BANKNIFTY");
        MessageDigest digest;
        try {digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        digest.update((symbol+"|"+inputMinutes+"|").getBytes(StandardCharsets.UTF_8));
        CsvCandleReader.Dataset parsed;
        try (var reader=new InputStreamReader(new DigestInputStream(input,digest),StandardCharsets.UTF_8)) {parsed=new CsvCandleReader().read(reader,inputMinutes);}
        String id=HexFormat.of().formatHex(digest.digest());
        var existing=datasets.findById(id);if(existing.isPresent()) return existing.get();
        var entity=new CsvBacktestDatasetEntity();entity.setId(id);entity.setSymbol(symbol);
        String safeName=Objects.toString(filename,"uploaded.csv").replace('\\','/');safeName=safeName.substring(safeName.lastIndexOf('/')+1);
        entity.setFilename(safeName.length()>200?safeName.substring(0,200):safeName);entity.setInputMinutes(inputMinutes);
        entity.setInputRows(parsed.rows());entity.setOutsideSession(parsed.outsideSession());entity.setIncompleteGroups(parsed.incompleteGroups());
        entity.setMissingSlots(parsed.missingFiveMinuteSlots());entity.setNormalizedTimestamps(parsed.normalizedTimestamps());entity.setCandleCount(parsed.candles().size());
        entity.setFirstDate(parsed.candles().get(0).date());entity.setLastDate(parsed.candles().get(parsed.candles().size()-1).date());
        entity.setImportedAt(LocalDateTime.now(StrategySupport.MARKET_ZONE));datasets.saveAndFlush(entity);
        jdbc.batchUpdate("insert into csv_backtest_candles(id,dataset_id,candle_time,open_price,high_price,low_price,close_price,volume) values (?,?,?,?,?,?,?,?)",
                parsed.candles(),1000,(statement,candle)-> {
                    var timestamp=LocalDateTime.of(candle.date(),candle.time());
                    statement.setString(1,id+"@"+timestamp);statement.setString(2,id);statement.setTimestamp(3,Timestamp.valueOf(timestamp));
                    statement.setDouble(4,candle.open());statement.setDouble(5,candle.high());statement.setDouble(6,candle.low());statement.setDouble(7,candle.close());statement.setLong(8,candle.volume());
                });
        return entity;
    }
    @Transactional(readOnly=true)
    public List<CsvBacktestDatasetEntity> list() {return datasets.findAllByOrderByImportedAtDesc();}
    public record Result(String datasetId,String pnlUnit,SupertrendStrategyProperties rules,SupertrendCsvBacktest.Report report) { }
    @Transactional(readOnly=true)
    public Result run(String id,LocalDate from,LocalDate to,double targetR,double slippagePoints,double costPoints,LocalTime squareOff) {
        var dataset=datasets.findById(id).orElseThrow(()->new IllegalArgumentException("Unknown dataset"));
        // Load all prior bars for exact 250-bar rolling initialization, bounded by the requested end date.
        var bars=jdbc.query("select candle_time,open_price,high_price,low_price,close_price,volume from csv_backtest_candles where dataset_id=? and candle_time<? order by candle_time",
                (row,index)->new StrategyCandle(row.getTimestamp(1).toLocalDateTime().toLocalDate(),row.getTimestamp(1).toLocalDateTime().toLocalTime(),
                        row.getDouble(2),row.getDouble(3),row.getDouble(4),row.getDouble(5),row.getLong(6)),id,Timestamp.valueOf((to==null?dataset.getLastDate():to).plusDays(1).atStartOfDay()));
        if (bars.isEmpty()) throw new IllegalArgumentException("No candles before the requested end date");
        var data=new CsvCandleReader.Dataset(bars,dataset.getInputRows(),dataset.getOutsideSession(),dataset.getIncompleteGroups(),dataset.getMissingSlots(),dataset.getNormalizedTimestamps());
        return new Result(id,"SPOT_INDEX_POINTS (not option P&L)",properties,engine.run(data,new SupertrendCsvBacktest.Config(dataset.getSymbol(),from,to,targetR,slippagePoints,costPoints,squareOff)));
    }
}
