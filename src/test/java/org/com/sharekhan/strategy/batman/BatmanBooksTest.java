package org.com.sharekhan.strategy.batman;

import org.junit.jupiter.api.*;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.*;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class BatmanBooksTest {
    final QuoteCacheService cache=mock(QuoteCacheService.class);
    final ScriptMasterRepository scripts=mock(ScriptMasterRepository.class);
    final ShoonyaQuoteService shoonya=mock(ShoonyaQuoteService.class);
    final WebSocketSubscriptionService feeds=mock(WebSocketSubscriptionService.class);
    final MStockDepthService mstock=mock(MStockDepthService.class);
    final BatmanBooks books=new BatmanBooks(cache,scripts,shoonya,feeds,mstock);
    final ScriptMasterEntity script=ScriptMasterEntity.builder().scripCode(1).exchange("NF").build();
    @BeforeEach void setup(){when(scripts.findByScripCode(1)).thenReturn(script);when(cache.getSharekhanSnapshot(1)).thenReturn(Optional.empty());}
    QuoteCacheService.QuoteSnapshot q(String source,double qty,Instant time){return QuoteCacheService.QuoteSnapshot.builder().scripCode(1)
        .bookSource(source).bookExchange("NF").bookProviderIdentity("scrip:1").coherentBook(true)
        .bestBid(99d).bestAsk(100d).bidQuantity(qty).askQuantity(qty).lastBookAt(time).build();}
    @Test void warmsAllLegsAndRetainsOnlyOneReferencePerKey(){
        var two=ScriptMasterEntity.builder().scripCode(2).exchange("NF").build();
        books.prepare(List.of(script,two));books.prepare(List.of(script,two));
        verify(feeds,times(1)).subscribeToScrip("NF1");verify(feeds,times(1)).subscribeToScrip("NF2");
        books.close();verify(feeds).unsubscribeFromScrip("NF1");verify(feeds).unsubscribeFromScrip("NF2");
    }
    @Test void freshSharekhanBookAvoidsRest(){var q=q("SHAREKHAN_WS",130,Instant.now());when(cache.getSharekhanSnapshot(1)).thenReturn(Optional.of(q));assertThat(books.book(1,"NF",false,130)).isSameAs(q);verifyNoInteractions(shoonya);}
    @Test void insufficientSharekhanDepthFallsBackToMstockFullBook(){
        when(cache.getSharekhanSnapshot(1)).thenReturn(Optional.of(q("SHAREKHAN_WS",65,Instant.now())));
        var depth=q("MSTOCK_WS",130,Instant.now());when(mstock.book(1)).thenReturn(depth);
        assertThat(books.book(1,"NF",false,130)).isSameAs(depth);verifyNoInteractions(shoonya);
    }
    @Test void staleMstockAndWrongShoonyaIdentityCannotBeUsed(){
        when(mstock.book(1)).thenReturn(q("MSTOCK_WS",130,Instant.now().minusSeconds(6)));
        when(shoonya.getExactQuote(script)).thenReturn(Optional.of(new ShoonyaQuoteService.ExactQuote(
            new ShoonyaQuoteService.LiveQuote("OPTION","1","Nifty 50","26000",25000d,24999d,25001d,1000d,1000d),"NSE",Instant.now(),Instant.now())));
        assertThat(books.book(1,"NF",false,130)).isNull();assertThat(books.reason(1)).contains("identity","quantity >=130");
    }
    @Test void mstockUnavailableFallsBackToExactShoonyaBook(){
        var updated=Instant.now().minusSeconds(1);
        when(shoonya.getExactQuote(script)).thenReturn(Optional.of(new ShoonyaQuoteService.ExactQuote(
            new ShoonyaQuoteService.LiveQuote("OPTION","1","OPTION","1",100d,99d,100d,130d,130d),"NFO",Instant.now(),updated)));
        var q=books.book(1,"NF",false,130);assertThat(q.getBookSource()).isEqualTo("SHOONYA");assertThat(q.getLastBookAt()).isEqualTo(updated);
        assertThat(books.book(1,"NF",false,130)).isSameAs(q);verify(shoonya,times(1)).getExactQuote(script);
    }
    @Test void neverAcceptsLtpOnlyOrNanQuantity(){assertThat(BatmanBooks.usable(q("SHAREKHAN_WS",Double.NaN,Instant.now()),Instant.now(),true,65)).isFalse();}
}
