package org.com.sharekhan.analysis;
import org.springframework.web.bind.annotation.*;import org.springframework.http.*;import org.springframework.security.core.Authentication;import java.util.Map;import static org.com.sharekhan.analysis.ThesisModels.*;
@RestController @RequestMapping("/api/stock-analysis/journal")
public class ThesisController {
 private final ThesisOwner owner;private final ThesisService service;
 public ThesisController(ThesisOwner owner,ThesisService service){this.owner=owner;this.service=service;}
 private ResponseEntity<?> response(Object o){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(o);}
 @GetMapping public ResponseEntity<?> book(Authentication a){return response(service.book(owner.resolve(a)));}
 @GetMapping("/sources") public ResponseEntity<?> sources(Authentication a){owner.resolve(a);return response(service.sourceOptions());}
 @GetMapping("/{id}") public ResponseEntity<?> get(Authentication a,@PathVariable String id){return response(service.get(owner.resolve(a),id));}
 @PostMapping public ResponseEntity<?> create(Authentication a,@RequestBody Create r){return response(service.create(owner.resolve(a),r));}
 @PutMapping("/{id}") public ResponseEntity<?> amend(Authentication a,@PathVariable String id,@RequestBody Amend r){return response(service.amend(owner.resolve(a),id,r));}
 @PostMapping("/{id}/transition") public ResponseEntity<?> transition(Authentication a,@PathVariable String id,@RequestBody Transition r){return response(service.transition(owner.resolve(a),id,r));}
 @PostMapping("/{id}/review") public ResponseEntity<?> review(Authentication a,@PathVariable String id,@RequestBody Review r){return response(service.review(owner.resolve(a),id,r));}
 @PostMapping("/{id}/postmortem") public ResponseEntity<?> postmortem(Authentication a,@PathVariable String id,@RequestBody Postmortem r){return response(service.postmortem(owner.resolve(a),id,r));}
 @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("message","Private journal unavailable; existing records were preserved. Retry before resubmitting."));}
}
