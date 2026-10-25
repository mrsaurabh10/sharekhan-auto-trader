package org.com.sharekhan.shadow;
import java.time.LocalDateTime;
import java.util.function.Supplier;
/** Carries the actual completed signal candle through synchronous request preparation. */
public final class MoverShadowSignalContext {
    private static final ThreadLocal<LocalDateTime> signal=new ThreadLocal<>();
    private MoverShadowSignalContext() { }
    public static LocalDateTime current(){return signal.get();}
    public static <T> T withSignal(LocalDateTime at,Supplier<T> work){
        LocalDateTime previous=signal.get();signal.set(at);
        try{return work.get();}finally{if(previous==null)signal.remove();else signal.set(previous);}
    }
}
