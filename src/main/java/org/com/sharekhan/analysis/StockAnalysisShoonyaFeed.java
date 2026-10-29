package org.com.sharekhan.analysis;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.auth.ShoonyaSavedSession;
import org.com.sharekhan.config.ShoonyaProperties;
import org.com.sharekhan.repository.AccessTokenRepository;
import org.com.sharekhan.service.SharekhanHistoricalService.HistoricalCandle;
import org.com.sharekhan.util.CryptoService;
import org.json.*;
import org.springframework.stereotype.Service;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;

/** Shared research feed only: uses the saved global session, never customer credentials or login. */
@Service
@RequiredArgsConstructor
public class StockAnalysisShoonyaFeed {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter INTRADAY = DateTimeFormatter.ofPattern("dd-MM-uuuu HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DAILY = new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("dd-MMM-uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    private final ShoonyaProperties properties;
    private final AccessTokenRepository tokens;
    private final CryptoService crypto;

    public List<HistoricalCandle> fetch(StockAnalysisService.Instrument instrument, String mode, Instant now) {
        try {
            var local = LocalDateTime.ofInstant(now, IST);
            var saved = ShoonyaSavedSession.currentGlobal(tokens.findAllByBrokerName("Shoonya"), local,
                    properties.getAccessTokenTtlSeconds()).orElseThrow(ShoonyaSavedSession.Unavailable::new);
            String session = Objects.requireNonNull(crypto.decrypt(saved.getToken()));
            String exchange = instrument.exchange().equals("NC") ? "NSE" : "BSE";
            String token = String.valueOf(instrument.scripCode());
            var identity = new JSONObject(post("GetSecurityInfo", new JSONObject().put("exch", exchange).put("token", token), session));
            String symbol = verifiedSymbol(identity, instrument);
            boolean intraday = mode.equals("INTRADAY");
            var request = new JSONObject();
            String start = String.valueOf(local.toLocalDate().minusDays(intraday ? 7 : 800).atStartOfDay(IST).toEpochSecond());
            String end = String.valueOf(now.getEpochSecond());
            if (intraday) request.put("exch", exchange).put("token", token).put("st", start).put("et", end).put("intrv", "5");
            else request.put("sym", exchange + ":" + symbol).put("from", start).put("to", end);
            return parse(post(intraday ? "TPSeries" : "EODChartData", request, session), intraday);
        } catch (Exception unavailable) {
            // Neither provider bodies nor credential-bearing exception messages leave this boundary.
            return List.of();
        }
    }

    static String verifiedSymbol(JSONObject response, StockAnalysisService.Instrument instrument) {
        String exchange = instrument.exchange().equals("NC") ? "NSE" : "BSE";
        String symbol = response.optString("tsym");
        String expected = instrument.symbol();
        boolean sameSymbol = symbol.equalsIgnoreCase(expected)
                || (exchange.equals("NSE") && symbol.equalsIgnoreCase(expected + "-EQ"));
        if (!"Ok".equals(response.optString("stat")) || !exchange.equals(response.optString("exch"))
                || !String.valueOf(instrument.scripCode()).equals(response.optString("token")) || !sameSymbol)
            throw new IllegalArgumentException("Cash instrument identity unavailable");
        return symbol;
    }

    static List<HistoricalCandle> parse(String body, boolean intraday) {
        var rows = new JSONArray(body);
        if (rows.length() > 2000) throw new IllegalArgumentException("Too many candles");
        var result = new ArrayList<HistoricalCandle>();
        for (int i = 0; i < rows.length(); i++) {
            Object value = rows.get(i);
            // EODChartData returns JSON-encoded objects inside its array in the live OAuth API.
            var row = value instanceof JSONObject object ? object : new JSONObject((String) value);
            if (row.has("stat") && !"Ok".equals(row.getString("stat"))) throw new IllegalArgumentException("Unsuccessful candle");
            LocalDateTime at = intraday ? LocalDateTime.parse(row.getString("time"), INTRADAY)
                    : LocalDate.parse(row.getString("time"), DAILY).atStartOfDay();
            var epoch = Instant.ofEpochSecond(Long.parseLong(row.getString("ssboe"))).atZone(IST);
            if (intraday ? !at.equals(epoch.toLocalDateTime()) : !at.toLocalDate().equals(epoch.toLocalDate()))
                throw new IllegalArgumentException("Inconsistent candle timestamp");
            Long volume = row.has("intv") ? new java.math.BigDecimal(row.get("intv").toString()).longValueExact() : null;
            result.add(new HistoricalCandle(at.toLocalDate(), at.toLocalTime(), row.getDouble("into"), row.getDouble("inth"),
                    row.getDouble("intl"), row.getDouble("intc"), volume));
        }
        return List.copyOf(result);
    }

    String post(String endpoint, JSONObject request, String session) throws Exception {
        if (!Set.of("GetSecurityInfo", "TPSeries", "EODChartData").contains(endpoint)) throw new IllegalArgumentException();
        var base = URI.create(properties.getApiUrl());
        if (!"https".equals(base.getScheme()) || !"api.shoonya.com".equals(base.getHost()) || base.getUserInfo() != null
                || properties.getUid() == null || properties.getUid().isBlank()) throw new IllegalStateException();
        var connection = (HttpURLConnection) URI.create(properties.getApiUrl() + "/" + endpoint).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000); connection.setReadTimeout(10000);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + session);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        request.put("uid", properties.getUid());
        try {
            // Match Shoonya's live OAuth transport and our existing verified candle integration.
            try (var out = connection.getOutputStream()) { out.write(("jData=" + request).getBytes(StandardCharsets.UTF_8)); }
            if (connection.getResponseCode() != 200) throw new IllegalStateException();
            try (var in = connection.getInputStream()) {
                byte[] bytes = in.readNBytes(2_000_001);
                if (bytes.length > 2_000_000) throw new IllegalStateException();
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
