package org.com.sharekhan.analysis;

import java.time.*;
import java.util.*;
import org.json.*;

/** Offline contract and backfill-boundary checks; no database or broker calls. */
public final class OptionHistoryArchiveTest {
    static int checks;
    static void check(boolean condition) { checks++;if(!condition)throw new AssertionError("Check "+checks); }
    static final OptionHistoryArchive.Job JOB=new OptionHistoryArchive.Job("NIFTY","WEEK",0,"CALL",LocalDate.of(2026,10,29),LocalDate.of(2026,10,30));
    static JSONObject source(String... clocks) {
        var b=new JSONObject().put("timestamp",new JSONArray());
        for(String f:OptionHistoryArchive.FIELDS)b.put(f,new JSONArray());
        for(String clock:clocks) {
            b.getJSONArray("timestamp").put(LocalDateTime.parse("2026-10-29T"+clock).atZone(OptionHistoryArchive.IST).toEpochSecond());
            for(String f:OptionHistoryArchive.FIELDS)b.getJSONArray(f).put(switch(f) {
                case "open","close" -> 0;
                case "high" -> 1;
                case "low","volume","oi","iv" -> 0;
                case "strike","spot" -> 22700;
                default -> throw new AssertionError();
            });
        }
        return new JSONObject().put("data",new JSONObject().put("ce",b).put("pe",JSONObject.NULL));
    }
    static JSONObject bars(JSONObject source) {return source.getJSONObject("data").getJSONObject("ce");}
    static void rejected(Runnable run) {boolean failed=false;try{run.run();}catch(RuntimeException e){failed=true;}check(failed);}
    public static void main(String[] args)throws Exception {
        var parsed=OptionHistoryArchive.parse(JOB,source("09:15","15:29","15:30","15:39"));
        check(parsed.rows().size()==4 && parsed.regular()==2 && parsed.invalid()==0); // Zero premium/OI valid.
        var duplicate=source("09:15","09:15");
        parsed=OptionHistoryArchive.parse(JOB,duplicate);
        check(parsed.rows().size()==1 && parsed.duplicates()==1);
        bars(duplicate).getJSONArray("close").put(1,0.5);
        parsed=OptionHistoryArchive.parse(JOB,duplicate);
        check(parsed.rows().isEmpty() && parsed.invalid()==2); // Neither conflicting price wins.
        var invalid=source("09:15","09:16");bars(invalid).getJSONArray("low").put(1,2);
        parsed=OptionHistoryArchive.parse(JOB,invalid);check(parsed.rows().size()==1 && parsed.invalid()==1);
        invalid=source("09:15");bars(invalid).getJSONArray("volume").put(0,0.5);
        check(OptionHistoryArchive.parse(JOB,invalid).invalid()==1);
        invalid=source("09:15");bars(invalid).getJSONArray("oi").put(0,-1);
        check(OptionHistoryArchive.parse(JOB,invalid).invalid()==1);
        invalid=source("09:15");bars(invalid).getJSONArray("strike").put(0,0);
        check(OptionHistoryArchive.parse(JOB,invalid).invalid()==1);
        invalid=source("09:15");bars(invalid).getJSONArray("timestamp").put(0,1);
        check(OptionHistoryArchive.parse(JOB,invalid).invalid()==1);
        invalid=source("09:15:01");check(OptionHistoryArchive.parse(JOB,invalid).invalid()==1);
        var mismatch=source("09:15");bars(mismatch).put("iv",new JSONArray());
        rejected(()->OptionHistoryArchive.parse(JOB,mismatch));
        var putJob=new OptionHistoryArchive.Job("BANKNIFTY","MONTH",-2,"PUT",JOB.from(),JOB.to());
        var put=source("09:15");put.getJSONObject("data").put("pe",bars(put)).put("ce",JSONObject.NULL);
        check(OptionHistoryArchive.parse(putJob,put).rows().size()==1);
        check(putJob.id().equals("25") && putJob.strike().equals("ATM-2"));
        check(!putJob.request().has("dhanClientId") && putJob.request().getInt("expiryCode")==1);
        check(OptionHistoryArchive.gunzip(OptionHistoryArchive.gzip(put.toString())).equals(put.toString()));
        var jobs=OptionHistoryArchive.jobs(OptionHistoryArchive.FROM,OptionHistoryArchive.THROUGH);
        var seen=new HashSet<String>();
        for(var j:jobs)for(var day=j.from();!day.isAfter(j.to());day=day.plusDays(1)) {
            if(!seen.add(j.symbol()+"/"+j.offset()+"/"+j.side()+"/"+day))throw new AssertionError("Overlapping range");
            if(j.symbol().equals("BANKNIFTY") && !j.family().equals(day.isBefore(OptionHistoryArchive.BANK_MONTH)?"WEEK":"MONTH"))
                throw new AssertionError("Wrong historical expiry family");
        }
        check(seen.size()==20*(java.time.temporal.ChronoUnit.DAYS.between(OptionHistoryArchive.FROM,OptionHistoryArchive.THROUGH)+1));
        check(jobs.stream().allMatch(j->!j.to().isAfter(j.from().plusDays(1))));
        rejected(()->OptionHistoryArchive.jobs(OptionHistoryArchive.FROM.minusDays(1),OptionHistoryArchive.THROUGH));
        rejected(()->OptionHistoryArchive.jobs(OptionHistoryArchive.FROM,OptionHistoryArchive.THROUGH.plusDays(1)));
        System.out.println(new JSONObject().put("offlineChecks",checks).put("jobs",jobs.size()).put("coveredSeriesDays",seen.size()).put("passed",true));
    }
}
