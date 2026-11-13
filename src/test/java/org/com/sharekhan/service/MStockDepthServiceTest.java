package org.com.sharekhan.service;

import org.junit.jupiter.api.*;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.com.sharekhan.util.CryptoService;
import java.time.*;
import java.nio.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class MStockDepthServiceTest {
    final TokenStoreService tokens=mock(TokenStoreService.class);
    final MStockInstrumentResolver resolver=mock(MStockInstrumentResolver.class);
    final MStockInstrumentRepository master=mock(MStockInstrumentRepository.class);
    final MStockDepthService service=new MStockDepthService(tokens,mock(CryptoService.class),resolver,master);
    final ScriptMasterEntity script=ScriptMasterEntity.builder().scripCode(1).tradingSymbol("NIFTY").exchange("NF").strikePrice(26400d).optionType("CE").expiry("29/12/2026").lotSize(65).build();
    @BeforeEach void setup(){
        when(resolver.resolveInstrumentKey(1)).thenReturn(Optional.of("NFO:NIFTY26DEC26400CE"));
        when(master.findByInstrumentKey(anyString())).thenReturn(Optional.of(MStockInstrumentEntity.builder().instrumentToken(64157L)
            .instrumentKey("NFO:NIFTY26DEC26400CE").exchange("NFO").strike(26400d).expiry("2026-12-29").lotSize(65).build()));
        service.watch(script);
    }
    byte[] frame(int size,long token,Instant time){
        var b=ByteBuffer.allocate(size+4).order(ByteOrder.BIG_ENDIAN);b.putShort((short)1).putShort((short)size);
        b.putInt(4,(int)token);
        if(size>=184){b.putInt(64,(int)(time.getEpochSecond()-315532800L));b.putInt(68,130);b.putInt(72,9900);b.putInt(128,65);b.putInt(132,10000);}
        return b.array();
    }
    @Test void acceptsOnlyMappedFullPacketWithExchangeTimestampAndDepth(){
        var now=Instant.now();service.accept(frame(184,64157,now.minusSeconds(1)),now);
        var q=service.book(1);assertThat(q.getBookSource()).isEqualTo("MSTOCK_WS");assertThat(q.getBestBid()).isEqualTo(99);
        assertThat(q.getBestAsk()).isEqualTo(100);assertThat(q.getBidQuantity()).isEqualTo(130);assertThat(q.getAskQuantity()).isEqualTo(65);
        assertThat(q.getLastBookAt()).isEqualTo(now.minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }
    @Test void acceptsExtendedFullPacket(){var now=Instant.now();service.accept(frame(200,64157,now.minusSeconds(1)),now);assertThat(service.book(1)).isNotNull();}
    @Test void rejectsLtpIndexWrongTokenStaleAndFuturePackets(){
        var now=Instant.now();for(int size:List.of(8,32,44,48))service.accept(frame(size,64157,now),now);
        service.accept(frame(184,26000,now),now);service.accept(frame(184,64157,now.minusSeconds(6)),now);service.accept(frame(184,64157,now.plusSeconds(2)),now);
        assertThat(service.book(1)).isNull();
    }
    @Test void truncatedPacketCannotCreateBook(){assertThatThrownBy(()->service.accept(new byte[]{0,1,0,(byte)184,0},Instant.now())).isInstanceOf(IllegalArgumentException.class);assertThat(service.book(1)).isNull();}
    @Test void existingSessionUnavailableDoesNotRefreshOrAuthenticate(){assertThat(service.availability()).contains("No existing");verify(tokens,times(1)).getFirstNonExpiredTokenInfo(any());verifyNoMoreInteractions(tokens);}
}
