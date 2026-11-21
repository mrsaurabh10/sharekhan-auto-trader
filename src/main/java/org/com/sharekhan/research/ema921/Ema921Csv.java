package org.com.sharekhan.research.ema921;
import java.io.*;
import java.time.*;
import java.util.*;
import static org.com.sharekhan.research.ema921.Ema921Engine.*;

/** Deliberately strict simple CSV: exact header, no quoted fields, ISO IST minute opens. */
public final class Ema921Csv {
    public static List<Bar> spot(Reader input) throws IOException {
        try {return read(input,"timestamp,open,high,low,close",500_000).stream().map(v->bar(v,0,1)).toList();}
        catch(DateTimeException e){throw new IllegalArgumentException("Expected ISO local minute timestamp in IST",e);}
    }
    public static List<OptionBar> options(Reader input) throws IOException {
        try {return read(input,"timestamp,contractId,listedOn,expiry,strike,right,lotSize,open,high,low,close",1_000_000).stream().map(v->
            new OptionBar(new Contract(v[1],LocalDate.parse(v[2]),LocalDate.parse(v[3]),Integer.parseInt(v[4]),v[5],Integer.parseInt(v[6])),bar(v,0,7))).toList();}
        catch(DateTimeException e){throw new IllegalArgumentException("Invalid ISO timestamp, listing date or expiry date",e);}
    }
    private static Bar bar(String[] v,int time,int price) {
        return new Bar(LocalDateTime.parse(v[time]),Double.parseDouble(v[price]),Double.parseDouble(v[price+1]),Double.parseDouble(v[price+2]),Double.parseDouble(v[price+3]));
    }
    private static List<String[]> read(Reader input,String header,int limit) throws IOException {
        var reader=new BufferedReader(input);String line=reader.readLine();
        if(line!=null&&line.startsWith("\ufeff"))line=line.substring(1);
        if(!header.equals(line))throw new IllegalArgumentException("Expected CSV header: "+header);
        var result=new ArrayList<String[]>();int fields=header.split(",").length;
        while((line=reader.readLine())!=null){
            if(line.isBlank())continue;
            if(line.length()>1000||line.contains("\""))throw new IllegalArgumentException("Invalid CSV row");
            var values=line.split(",",-1);if(values.length!=fields)throw new IllegalArgumentException("Invalid CSV column count");
            result.add(values);if(result.size()>limit)throw new IllegalArgumentException("CSV row limit exceeded");
        }
        return result;
    }
}
