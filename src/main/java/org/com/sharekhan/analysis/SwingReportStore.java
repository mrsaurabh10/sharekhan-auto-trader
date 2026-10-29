package org.com.sharekhan.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.com.sharekhan.analysis.SwingModels.*;

/** Small public research archives on the existing persistent data mount. No account data. */
@Component
public class SwingReportStore {
    private final Path directory;
    private final ObjectMapper mapper;
    public SwingReportStore(ObjectMapper mapper,@Value("${app.swing.directory:/app/data/swing-ideas}") String directory) {
        this.mapper=mapper;this.directory=Path.of(directory);
    }
    public synchronized Optional<Report> read(LocalDate day) {
        try {
            Path path=directory.resolve(day+".json");
            if(!Files.exists(path))return Optional.empty();
            if(Files.size(path)>1_000_000)throw new IllegalStateException("Research report too large");
            return Optional.of(mapper.readValue(Files.readAllBytes(path),Report.class));
        }catch(Exception e){throw new IllegalStateException("Unable to read saved swing report");}
    }
    public synchronized List<Report> history() {
        if(!Files.exists(directory))return List.of();
        try(var files=Files.list(directory)) {
            return files.map(p->p.getFileName().toString()).filter(n->n.matches("\\d{4}-\\d{2}-\\d{2}\\.json"))
                    .sorted(Comparator.reverseOrder()).limit(90).map(n->read(LocalDate.parse(n.substring(0,10))).orElseThrow()).toList();
        }catch(Exception e){throw new IllegalStateException("Unable to list saved swing reports");}
    }
    public synchronized void save(Report report) {
        var existing=read(report.dataDate());
        if(existing.isPresent()&&!existing.get().status().equals("DATA_INCOMPLETE"))return; // immutable published plans
        write(report.dataDate()+".json",report);
        try(var files=Files.list(directory)) {
            var cutoff=report.dataDate().minusDays(180);
            for(Path p:files.filter(p->p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.json")).toList())
                if(LocalDate.parse(p.getFileName().toString().substring(0,10)).isBefore(cutoff))Files.delete(p);
        }catch(Exception e){throw new IllegalStateException("Unable to retain swing archives");}
    }
    public synchronized void saveObservations(List<Observation> observations) { write("observations.json",observations); }
    public synchronized List<Observation> observations() {
        Path p=directory.resolve("observations.json");if(!Files.exists(p))return List.of();
        try {
            if(Files.size(p)>1_000_000)throw new IllegalStateException();
            return Arrays.asList(mapper.readValue(Files.readAllBytes(p),Observation[].class));
        }catch(Exception e){throw new IllegalStateException("Unable to read swing observations");}
    }
    private void write(String name,Object value) {
        Path temporary=null;
        try {
            Files.createDirectories(directory);byte[] bytes=mapper.writeValueAsBytes(value);
            if(bytes.length>1_000_000)throw new IllegalStateException();
            temporary=Files.createTempFile(directory,".swing-",".tmp");Files.write(temporary,bytes);
            Files.move(temporary,directory.resolve(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception e){throw new IllegalStateException("Unable to save swing research");}
        finally {if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){}}
    }
}
