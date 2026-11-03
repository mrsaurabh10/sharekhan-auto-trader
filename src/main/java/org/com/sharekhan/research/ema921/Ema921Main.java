package org.com.sharekhan.research.ema921;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.*;
import java.util.*;
/** Offline research CLI: spot.csv config.json report.json [options.csv]. No Spring startup. */
public final class Ema921Main {
    public static void main(String[] args)throws Exception{
        if(args.length<3||args.length>4)throw new IllegalArgumentException("spot.csv config.json report.json [options.csv]");
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        List<Ema921Engine.Bar> spot;try(var reader=Files.newBufferedReader(Path.of(args[0]))){spot=Ema921Csv.spot(reader);}
        var config=mapper.readValue(Path.of(args[1]).toFile(),Ema921Engine.Config.class);
        var options=List.<Ema921Engine.OptionBar>of();if(args.length==4)try(var reader=Files.newBufferedReader(Path.of(args[3]))){options=Ema921Csv.options(reader);}
        var report=new Ema921Engine().run(spot,options,config);
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(),report);
        System.out.println("Sessions="+report.days().size()+" trades="+report.trades().size()+" evidence="+report.evidenceStatus());
    }
}
