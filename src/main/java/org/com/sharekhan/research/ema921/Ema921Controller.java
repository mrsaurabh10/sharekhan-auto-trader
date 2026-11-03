package org.com.sharekhan.research.ema921;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Controller
public class Ema921Controller {
    private final ObjectMapper mapper;
    public Ema921Controller(ObjectMapper mapper){this.mapper=mapper;}
    @GetMapping("/strategy-research/ema921") public String page(){return "ema921";}
    @PostMapping("/api/strategy-research/ema921") @ResponseBody
    public ResponseEntity<?> replay(@RequestParam MultipartFile spot,@RequestParam(required=false) MultipartFile options,@RequestParam String config) {
        try {
            if(spot.getSize()>64_000_000||(options!=null&&options.getSize()>96_000_000)||config.length()>100_000)throw new IllegalArgumentException("Input size limit exceeded");
            var settings=mapper.readValue(config,Ema921Engine.Config.class);
            if(settings==null)throw new IllegalArgumentException("Config is required");
            try(var reader=new InputStreamReader(spot.getInputStream(),StandardCharsets.UTF_8)) {
                var bars=Ema921Csv.spot(reader);var quotes=List.<Ema921Engine.OptionBar>of();
                if(options!=null&&!options.isEmpty())try(var optionReader=new InputStreamReader(options.getInputStream(),StandardCharsets.UTF_8)){quotes=Ema921Csv.options(optionReader);}
                return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Ema921Engine().run(bars,quotes,settings));
            }
        } catch(IOException|IllegalArgumentException e){return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("error","Invalid input: "+Objects.toString(e.getMessage(),"invalid file").substring(0,Math.min(500,Objects.toString(e.getMessage(),"invalid file").length()))));}
    }
}
