package org.com.sharekhan.analysis;
import java.time.*;import java.util.*;
/** Research-only journal contract; deliberately no fills, shares, broker IDs or P&L. */
public final class ThesisModels {
 private ThesisModels(){}
 public static final String VERSION="NSE_RESEARCH_THESIS_V1";
 public record Source(String origin,String sourceId,String symbol,LocalDate setupDate,Integer score,String marketGate,String inputHash,Double referenceClose,Double referenceLow,FluencyService.Candidate rawCandidate){}
 public record Event(int revision,Instant at,String action,String fromState,String toState,String note,String reviewResult,LocalDate nextReview,String thesis,String entryCondition,String invalidation,String outcome,String lessons){}
 public record Entry(String version,String id,String kind,int revision,String creationFingerprint,Instant createdAt,Instant updatedAt,String symbol,String thesisType,String state,String thesis,String entryCondition,String invalidation,LocalDate nextReview,String reviewResult,String outcome,String lessons,Source source,List<Event> history){}
 public record Create(String requestId,String symbol,String thesisType,String sourceId,String thesis,String entryCondition,String invalidation,LocalDate nextReview){}
 public record Amend(int expectedRevision,String thesis,String entryCondition,String invalidation,LocalDate nextReview,String note){}
 public record Transition(int expectedRevision,String target,String reason,String outcome,String lessons){}
 public record Review(int expectedRevision,String result,LocalDate nextReview,String note){}
 public record Postmortem(int expectedRevision,String note){}
 public record Book(String version,LocalDate today,int dueCount,List<Entry> entries,List<String> rules){}
 public record SourceOption(String sourceId,String symbol,LocalDate setupDate,int score,String marketGate){}
}
