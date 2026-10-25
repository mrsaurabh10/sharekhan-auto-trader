package org.com.sharekhan.shadow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static org.com.sharekhan.shadow.ShadowModels.*;

/** Uses only dedicated research tables, always on the independent writer/report thread. */
@Component
public class MoverShadowStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public MoverShadowStore(DataSource dataSource, ObjectMapper mapper) {
        jdbc = new JdbcTemplate(dataSource); jdbc.setQueryTimeout(2); this.mapper = mapper;
    }
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Unable to encode shadow observation", e); }
    }
    public <T> T read(String json, Class<T> type) {
        try { return mapper.readValue(json,type); }
        catch (Exception e) { throw new IllegalArgumentException("Unable to decode shadow observation", e); }
    }
    public void append(List<Event> rows) {
        jdbc.batchUpdate("INSERT INTO mover_shadow_events(id,trading_day,event_key,event_type,app_user_id,observed_at,payload) VALUES(?,?,?,?,?,?,?)",
            rows,100,(p,r)-> {p.setString(1,r.id());p.setObject(2,r.day());p.setString(3,r.key());p.setString(4,r.type());
                p.setObject(5,r.userId());p.setObject(6,OffsetDateTime.ofInstant(r.at(),ZoneOffset.UTC));p.setString(7,r.payload());});
    }
    public List<Event> events(LocalDate day, String type) {
        return jdbc.query("SELECT id,trading_day,event_key,event_type,app_user_id,observed_at,payload FROM mover_shadow_events WHERE trading_day=? AND event_type=? ORDER BY observed_at,id",
            (r,n)->new Event(r.getString(1),r.getObject(2,LocalDate.class),r.getString(3),r.getString(4),r.getObject(5,Long.class),r.getObject(6,OffsetDateTime.class).toInstant(),r.getString(7)),day,type);
    }
    public List<Decision> decisions(LocalDate day, Long user) {
        return jdbc.query("SELECT payload FROM mover_shadow_events WHERE trading_day=? AND event_type='DECISION' AND app_user_id=? ORDER BY observed_at,id",
            (r,n)->read(r.getString(1),Decision.class),day,user);
    }
    public Set<Long> users(LocalDate day) { return new HashSet<>(jdbc.queryForList("SELECT DISTINCT app_user_id FROM mover_shadow_events WHERE trading_day=? AND event_type='DECISION'",Long.class,day)); }
    public long bytes() {
        // PostgreSQL physical size includes indexes, dead tuples and reports; H2 test fallback is conservative logical size.
        String product=jdbc.execute((java.sql.Connection c)->c.getMetaData().getDatabaseProductName());
        if ("PostgreSQL".equals(product)) return jdbc.queryForObject("SELECT pg_total_relation_size('mover_shadow_events')+pg_total_relation_size('mover_shadow_reports')",Long.class);
        return jdbc.queryForObject("SELECT COALESCE(SUM(OCTET_LENGTH(payload)),0) FROM mover_shadow_events",Long.class);
    }
    public long[] dailyUsage(LocalDate day) {
        return jdbc.queryForObject("SELECT COUNT(*),COALESCE(SUM(OCTET_LENGTH(payload)),0) FROM mover_shadow_events WHERE trading_day=?",(r,n)->new long[]{r.getLong(1),r.getLong(2)},day);
    }
    public void saveReport(LocalDate day, Long user, String payload) {
        jdbc.update("DELETE FROM mover_shadow_reports WHERE trading_day=? AND app_user_id=?",day,user);
        jdbc.update("INSERT INTO mover_shadow_reports(trading_day,app_user_id,payload) VALUES(?,?,?)",day,user,payload);
    }
    public Optional<String> archived(LocalDate day,Long user) {
        return jdbc.queryForList("SELECT payload FROM mover_shadow_reports WHERE trading_day=? AND app_user_id=?",String.class,day,user).stream().findFirst();
    }
    public List<String> history(Long user) {
        return jdbc.queryForList("SELECT payload FROM mover_shadow_reports WHERE app_user_id=? ORDER BY trading_day",String.class,user);
    }
    public void retain(LocalDate rawCutoff, LocalDate reportCutoff) {
        jdbc.update("DELETE FROM mover_shadow_events WHERE trading_day<?",rawCutoff);
        jdbc.update("DELETE FROM mover_shadow_reports WHERE trading_day<?",reportCutoff);
    }
}
