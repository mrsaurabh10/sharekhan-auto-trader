package org.com.sharekhan.analysis;

import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.json.*;

/** Explicit historical reads and additive archive writes. No Spring, auth refresh, or orders. */
public final class OptionHistoryArchive {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate FROM = LocalDate.of(2021,11,1), THROUGH = LocalDate.of(2026,10,30);
    static final LocalDate BANK_MONTH = LocalDate.of(2024,11,14);
    static final List<String> FIELDS = List.of("open","high","low","close","volume","strike","spot","oi","iv");
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    static final AtomicBoolean STOP = new AtomicBoolean();
    static final AtomicInteger CALLS = new AtomicInteger(), SAVED = new AtomicInteger(), REUSED = new AtomicInteger();
    static final Object PACER = new Object();
    static long nextRequest;
    static PrintStream output;

    record Job(String symbol, String family, int offset, String side, LocalDate from, LocalDate to) {
        String id() { return symbol.equals("NIFTY") ? "13" : "25"; }
        String strike() { return offset == 0 ? "ATM" : "ATM" + (offset > 0 ? "+" : "") + offset; }
        String key() { return id()+"_"+family+"_1_"+strike()+"_"+side+"_"+from+"_"+to; }
        JSONObject request() {
            return new JSONObject().put("exchangeSegment","NSE_FNO").put("interval","1")
                .put("securityId",id()).put("instrument","OPTIDX").put("expiryFlag",family)
                .put("expiryCode",1).put("strike",strike()).put("drvOptionType",side)
                .put("requiredData",new JSONArray(FIELDS)).put("fromDate",from.toString()).put("toDate",to.toString());
        }
    }
    record Parsed(List<JSONObject> rows, int source, int invalid, int duplicates, JSONObject quality) {
        int regular() { return (int) rows.stream().filter(r -> r.getString("session_flag").equals("REGULAR")).count(); }
    }
    static Connection db() throws SQLException {
        return DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),
            System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD"));
    }
    static byte[] gzip(String value) throws IOException {
        var b = new ByteArrayOutputStream();
        try (var g = new GZIPOutputStream(b)) { g.write(value.getBytes(StandardCharsets.UTF_8)); }
        return b.toByteArray();
    }
    static String gunzip(byte[] value) throws IOException {
        try (var g = new GZIPInputStream(new ByteArrayInputStream(value))) {
            byte[] b = g.readNBytes(6_000_001);
            if (b.length > 6_000_000) throw new IOException("Oversized source");
            return new String(b, StandardCharsets.UTF_8);
        }
    }
    static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
    static BigDecimal decimal(JSONArray values, int index) {
        return new BigDecimal(values.get(index).toString());
    }
    static boolean sameRow(JSONObject a,JSONObject b) {
        if(a.getLong("epoch")!=b.getLong("epoch") || !a.getString("session_flag").equals(b.getString("session_flag")))return false;
        for(String f:FIELDS)if(a.getBigDecimal(f).compareTo(b.getBigDecimal(f))!=0)return false;
        return true;
    }
    static boolean sameRequest(JSONObject a,JSONObject b) {
        if(!a.keySet().equals(b.keySet()))return false;
        for(String k:a.keySet())if(!a.get(k).toString().equals(b.get(k).toString()))return false;
        return true;
    }
    static Parsed parse(Job job, JSONObject response) {
        var data = response.getJSONObject("data");
        var bars = data.getJSONObject(job.side.equals("CALL") ? "ce" : "pe");
        var ts = bars.getJSONArray("timestamp");
        int n = ts.length();
        if (n > 3000) throw new IllegalArgumentException("Oversized chunk");
        for (String f : FIELDS) if (bars.getJSONArray(f).length() != n)
            throw new IllegalArgumentException("Array length mismatch");
        var rows = new TreeMap<Long,JSONObject>();
        var conflicted = new HashSet<Long>();
        int invalid = 0, duplicates = 0;
        for (int i=0;i<n;i++) {
            try {
                long epoch = decimal(ts,i).longValueExact();
                var time = Instant.ofEpochSecond(epoch).atZone(IST).toLocalDateTime();
                if (time.toLocalDate().isBefore(job.from) || time.toLocalDate().isAfter(job.to)
                        || time.getSecond()!=0 || time.getNano()!=0) throw new IllegalArgumentException();
                var row = new JSONObject().put("epoch",epoch).put("time_ist",time.toString());
                for (String f:FIELDS) {
                    var number = decimal(bars.getJSONArray(f),i);
                    if (number.signum()<0 || (Set.of("spot","strike").contains(f) && number.signum()==0))
                        throw new IllegalArgumentException();
                    if (Set.of("volume","oi").contains(f)) number.longValueExact();
                    row.put(f,number);
                }
                var o=row.getBigDecimal("open"); var h=row.getBigDecimal("high");
                var l=row.getBigDecimal("low"); var c=row.getBigDecimal("close");
                if (h.compareTo(o)<0 || h.compareTo(c)<0 || h.compareTo(l)<0 || l.compareTo(o)>0 || l.compareTo(c)>0)
                    throw new IllegalArgumentException();
                boolean weekday=time.getDayOfWeek().getValue()<=5;
                boolean regular=!time.toLocalTime().isBefore(LocalTime.of(9,15)) && time.toLocalTime().isBefore(LocalTime.of(15,30));
                row.put("session_flag", weekday && regular ? "REGULAR" : "SPECIAL_OR_OUT_OF_HOURS_REVIEW");
                if (conflicted.contains(epoch)) { invalid++; continue; }
                var old=rows.get(epoch);
                if (old!=null) {
                    if (sameRow(old,row)) duplicates++;
                    else { rows.remove(epoch); conflicted.add(epoch); invalid+=2; }
                } else rows.put(epoch,row);
            } catch (RuntimeException e) { invalid++; }
        }
        var days=new JSONObject();
        for(var row:rows.values()) {
            String day=row.getString("time_ist").substring(0,10);
            days.put(day,days.optInt(day)+1);
        }
        var quality=new JSONObject().put("dates",days).put("expiryIdentity","NOT_PROVIDED_BY_SOURCE")
                .put("sessionCalendar","REGULAR_CLOCK_ONLY_SPECIALS_REQUIRE_REVIEW")
                .put("strikeChangesWithinChunk",0).put("conflictingTimestamps",conflicted.size());
        BigDecimal previous=null; int changes=0;
        for(var row:rows.values()) { var strike=row.getBigDecimal("strike"); if(previous!=null && previous.compareTo(strike)!=0) changes++; previous=strike; }
        quality.put("strikeChangesWithinChunk",changes);
        return new Parsed(List.copyOf(rows.values()),n,invalid,duplicates,quality);
    }
    static synchronized void emit(JSONObject status) { output.println(status); }
    static void diskGuard() throws IOException {
        long free=Files.getFileStore(Path.of("/app/data")).getUsableSpace();
        if (free < 700L*1024*1024) { STOP.set(true); throw new IOException("Storage reserve reached"); }
    }
    static String decrypt(String encrypted,String key) throws Exception {
        byte[] all=Base64.getDecoder().decode(encrypted);
        if(all.length<29) throw new IllegalStateException();
        var cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(Base64.getDecoder().decode(key),"AES"),
                new GCMParameterSpec(128,Arrays.copyOf(all,12)));
        return new String(cipher.doFinal(Arrays.copyOfRange(all,12,all.length)),StandardCharsets.UTF_8);
    }
    static String[] credentials() throws Exception {
        String selected=System.getenv("APP_STOCK_HISTORY_DHAN_CREDENTIAL_ID");
        if(selected==null || !selected.matches("[0-9]+")) throw new IllegalStateException();
        String token,client;
        try(var c=db();var q=c.prepareStatement("select b.client_code,t.token from broker_credentials b join lateral "
                +"(select token from access_token where broker_credentials_id=b.id and lower(broker_name)='dhan' "
                +"and expiry>now()+interval '60 seconds' order by expiry desc limit 1) t on true "
                +"where b.id=? and lower(b.broker_name)='dhan' and b.active=true")) {
            q.setLong(1,Long.parseLong(selected));var r=q.executeQuery();
            if(!r.next()) throw new IllegalStateException("Saved token unavailable");
            client=r.getString(1);token=r.getString(2);
        }
        String key=System.getenv("APP_CRYPTO_KEY");
        if(key==null || key.isBlank()) {
            String yaml=new String(Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream("application.yml").readAllBytes(),StandardCharsets.UTF_8);
            var matcher=java.util.regex.Pattern.compile("(?m)^  crypto:\\s*\\n    key:\\s*([^\\n]+)").matcher(yaml);
            if(!matcher.find())throw new IllegalStateException();
            key=matcher.group(1).trim().replace("\"","").replace("'","");
        }
        return new String[]{decrypt(client,key).trim(),decrypt(token,key)};
    }
    static JSONObject fetch(Job job) throws Exception {
        diskGuard();
        // One process, three workers; starts paced below three data requests/second.
        synchronized(PACER) {
            long wait=nextRequest-System.nanoTime();
            if(wait>0) TimeUnit.NANOSECONDS.sleep(wait);
            nextRequest=System.nanoTime()+350_000_000L;
        }
        if(STOP.get()) throw new InterruptedException();
        String[] creds=credentials();
        var wire=job.request().put("dhanClientId",creds[0]); // Never retained in the archive.
        var request=HttpRequest.newBuilder(URI.create("https://api.dhan.co/v2/charts/rollingoption"))
                .timeout(Duration.ofSeconds(45)).header("access-token",creds[1]).header("client-id",creds[0])
                .header("Accept","application/json").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(wire.toString())).build();
        CALLS.incrementAndGet();
        var res=HTTP.send(request,HttpResponse.BodyHandlers.ofInputStream());
        var result=new JSONObject().put("http",res.statusCode());
        try(var stream=res.body()) {
            byte[] bytes=stream.readNBytes(6_000_001);
            if(bytes.length>6_000_000) throw new IOException("Oversized source");
            try {
                var parsed=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
                if(res.statusCode()==200 && parsed.has("data")) result.put("data",parsed.get("data"));
                else result.put("errorCode",parsed.optString("errorCode","UNSPECIFIED").replaceAll("[^A-Za-z0-9_-]",""));
            } catch(JSONException e) { result.put("format","NON_JSON"); }
        }
        if(Set.of(401,403,429).contains(res.statusCode())) STOP.set(true);
        return new JSONObject().put("request",job.request()).put("capturedAt",Instant.now().toString()).put("response",result);
    }
    static JSONObject existing(Job job) throws Exception {
        try(var c=db();var q=c.prepareStatement("select response_gzip from option_history_chunks where chunk_key=?")) {
            q.setString(1,job.key());var r=q.executeQuery();
            return r.next() ? new JSONObject(gunzip(r.getBytes(1))) : null;
        }
    }
    static String store(Job job,JSONObject saved) throws Exception {
        if(!sameRequest(saved.getJSONObject("request"),job.request()))throw new IllegalArgumentException("Identity mismatch");
        var response=saved.getJSONObject("response");
        int http=response.getInt("http");
        Parsed parsed=new Parsed(List.of(),0,0,0,new JSONObject());
        String status="ERROR";
        if(http==200) {
            try {
                parsed=parse(job,response);
                status=parsed.source==0 ? "EMPTY" : parsed.invalid>0 ? "PARTIAL" : "OK";
            } catch(RuntimeException e) { status="QUARANTINED"; }
        } else if(http==400 && "DH-905".equals(response.optString("errorCode")) && job.from.isBefore(job.to)) status="SPLIT";
        byte[] gz=gzip(saved.toString());
        diskGuard();
        try(var c=db()) {
            c.setAutoCommit(false);
            try(var s=c.createStatement()) { s.execute("set local lock_timeout='3s'");s.execute("set local statement_timeout='15s'"); }
            String sql="insert into option_history_chunks(chunk_key,underlying,security_id,expiry_family,expiry_code,strike_offset,option_side,requested_from,requested_to_inclusive,public_request,captured_at,http_status,status,source_bars,valid_bars,regular_bars,invalid_bars,duplicate_bars,first_epoch,last_epoch,quality,response_sha256,response_gzip) values (?,?,?,?,1,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?) on conflict(chunk_key) do nothing";
            try(var q=c.prepareStatement(sql)) {
                q.setString(1,job.key());q.setString(2,job.symbol);q.setString(3,job.id());q.setString(4,job.family);
                q.setInt(5,job.offset);q.setString(6,job.side);q.setObject(7,job.from);q.setObject(8,job.to);
                q.setString(9,job.request().toString());q.setObject(10,OffsetDateTime.parse(saved.getString("capturedAt")));
                q.setInt(11,http);q.setString(12,status);q.setInt(13,parsed.source);q.setInt(14,parsed.rows.size());
                q.setInt(15,parsed.regular());q.setInt(16,parsed.invalid);q.setInt(17,parsed.duplicates);
                if(parsed.rows.isEmpty()) { q.setNull(18,Types.BIGINT);q.setNull(19,Types.BIGINT); }
                else {q.setLong(18,parsed.rows.getFirst().getLong("epoch"));q.setLong(19,parsed.rows.getLast().getLong("epoch"));}
                q.setString(20,parsed.quality.toString());q.setString(21,sha(gz));q.setBytes(22,gz);
                int inserted=q.executeUpdate();
                if(inserted==0) {
                    try(var verify=c.prepareStatement("select response_sha256 from option_history_chunks where chunk_key=?")) {
                        verify.setString(1,job.key());var r=verify.executeQuery();
                        if(!r.next() || !r.getString(1).equals(sha(gz)))throw new IllegalStateException("Conflicting archive");
                    }
                } else SAVED.incrementAndGet();
            }
            c.commit();
        }
        return status;
    }
    static void process(Job job,Path seeds,boolean replay) throws Exception {
        if(STOP.get()) return;
        var saved=existing(job);String status;
        if(saved!=null) { REUSED.incrementAndGet();
            int http=saved.getJSONObject("response").getInt("http");
            status=http==400 && "DH-905".equals(saved.getJSONObject("response").optString("errorCode")) && job.from.isBefore(job.to) ? "SPLIT":"EXISTING";
        } else {
            var file=seeds.resolve(job.key()+".json.gz");
            if(Files.exists(file)) saved=new JSONObject(gunzip(Files.readAllBytes(file)));
            else { if(replay) throw new IllegalStateException("Replay missing checkpoint"); saved=fetch(job); }
            status=store(job,saved);
        }
        if(status.equals("SPLIT")) {
            process(new Job(job.symbol,job.family,job.offset,job.side,job.from,job.from),seeds,replay);
            process(new Job(job.symbol,job.family,job.offset,job.side,job.to,job.to),seeds,replay);
        }
        if(SAVED.get()%50==0 || Set.of(401,403,429).contains(saved.getJSONObject("response").getInt("http")))
            emit(new JSONObject().put("last",job.key()).put("saved",SAVED.get()).put("reused",REUSED.get()).put("apiCalls",CALLS.get()).put("stop",STOP.get()));
    }
    static List<Job> jobs(LocalDate from,LocalDate through) {
        if(from.isBefore(FROM) || through.isAfter(THROUGH) || from.isAfter(through))throw new IllegalArgumentException();
        var jobs=new ArrayList<Job>();
        for(var day=from;!day.isAfter(through);) {
            var end=day.plusDays(1).isAfter(through)?through:day.plusDays(1);
            // Keep both underlyings on the same boundaries without crossing BANKNIFTY's last weekly expiry.
            if(day.equals(BANK_MONTH.minusDays(1)))end=day;
            if(day.isBefore(BANK_MONTH) && !end.isBefore(BANK_MONTH))end=BANK_MONTH.minusDays(1);
            for(String symbol:List.of("NIFTY","BANKNIFTY"))for(int offset:List.of(0,-1,1,-2,2))for(String side:List.of("CALL","PUT"))
                jobs.add(new Job(symbol,symbol.equals("BANKNIFTY")&&!day.isBefore(BANK_MONTH)?"MONTH":"WEEK",offset,side,day,end));
            day=end.plusDays(1);
        }
        return jobs;
    }
    static void export(String symbol,int offset,String side,LocalDate day) throws Exception {
        if(!Set.of("NIFTY","BANKNIFTY").contains(symbol)||!Set.of("CALL","PUT").contains(side)||offset < -2||offset>2)throw new IllegalArgumentException();
        var rows=new TreeMap<Long,JSONObject>();
        try(var c=db();var q=c.prepareStatement("select expiry_family,requested_from,requested_to_inclusive,response_gzip,response_sha256 from option_history_chunks where underlying=? and strike_offset=? and option_side=? and requested_from<=? and requested_to_inclusive>=? and status in ('OK','PARTIAL')")) {
            q.setString(1,symbol);q.setInt(2,offset);q.setString(3,side);q.setObject(4,day);q.setObject(5,day);var r=q.executeQuery();
            while(r.next()) {
                byte[] raw=r.getBytes(4);if(!sha(raw).equals(r.getString(5)))throw new IllegalStateException("Checksum mismatch");
                var job=new Job(symbol,r.getString(1),offset,side,r.getObject(2,LocalDate.class),r.getObject(3,LocalDate.class));
                for(var row:parse(job,new JSONObject(gunzip(raw)).getJSONObject("response")).rows) {
                    if(!row.getString("time_ist").startsWith(day.toString()))continue;
                    var prior=rows.putIfAbsent(row.getLong("epoch"),row);
                    if(prior!=null && !sameRow(prior,row))throw new IllegalStateException("Overlapping source conflict");
                }
            }
        }
        if(rows.isEmpty())throw new IllegalStateException("No validated rows");
        output.println("time_ist,epoch,underlying,relative_strike,side,open,high,low,close,volume,strike,spot,oi,iv,session_flag,expiry_identity");
        for(var row:rows.values()) {
            var values=new ArrayList<String>(List.of(row.getString("time_ist"),row.get("epoch").toString(),symbol,Integer.toString(offset),side));
            for(String f:FIELDS)values.add(row.get(f).toString());
            values.add(row.getString("session_flag"));values.add("UNKNOWN");output.println(String.join(",",values));
        }
    }
    public static void main(String[] args) {
        output=System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));System.setErr(System.out);
        try {
            switch(args[0]) {
                case "export" -> export(args[1],Integer.parseInt(args[2]),args[3],LocalDate.parse(args[4]));
                case "import", "replay" -> {
                    boolean replay=args[0].equals("replay");
                    var work=jobs(LocalDate.parse(args[2]),LocalDate.parse(args[3]));
                    int maximum=args.length>4?Integer.parseInt(args[4]):work.size();
                    if(maximum<0)throw new IllegalArgumentException();
                    work=work.subList(0,Math.min(maximum,work.size()));
                    try(var lock=db();var s=lock.createStatement()) {
                        var locked=s.executeQuery("select pg_try_advisory_lock(20261101,707)");
                        if(!locked.next()||!locked.getBoolean(1))throw new IllegalStateException("Archive already running");
                        var pool=Executors.newFixedThreadPool(3);
                        var failures=new AtomicInteger();
                        for(var job:work) pool.submit(()-> {
                            try { process(job,Path.of(args[1]),replay); }
                            catch(Exception e) { STOP.set(true);failures.incrementAndGet();
                                emit(new JSONObject().put("chunk",job.key()).put("errorType",e.getClass().getSimpleName())
                                        .put("sqlState",e instanceof SQLException sql ? sql.getSQLState():"")); }
                        });
                        pool.shutdown();while(!pool.awaitTermination(30,TimeUnit.SECONDS))
                            emit(new JSONObject().put("saved",SAVED.get()).put("reused",REUSED.get()).put("apiCalls",CALLS.get()).put("stop",STOP.get()));
                        emit(new JSONObject().put("finished",true).put("success",!STOP.get()&&failures.get()==0)
                            .put("planned",work.size()).put("saved",SAVED.get()).put("reused",REUSED.get()).put("apiCalls",CALLS.get()));
                        if(STOP.get()||failures.get()>0)System.exit(1);
                    }
                }
                default -> throw new IllegalArgumentException();
            }
        } catch(Exception e) { emit(new JSONObject().put("success",false).put("errorType",e.getClass().getSimpleName()));System.exit(1); }
    }
}
