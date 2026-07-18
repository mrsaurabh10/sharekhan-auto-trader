package org.com.sharekhan.strategy;

import org.com.sharekhan.entity.ScriptMasterEntity;

/** A name selected by the 09:25 F&O mover scan and awaiting entry qualification. */
public record Fno925Candidate(String symbol,
                              ScriptMasterEntity spot,
                              String optionType) {
}
