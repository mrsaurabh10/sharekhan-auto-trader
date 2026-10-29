package org.com.sharekhan.analysis;

import org.com.sharekhan.config.ShoonyaProperties;
import org.com.sharekhan.entity.AccessTokenEntity;
import org.com.sharekhan.repository.AccessTokenRepository;
import org.com.sharekhan.util.CryptoService;
import org.json.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockAnalysisShoonyaFeedTest {
    private final StockAnalysisService.Instrument nse = new StockAnalysisService.Instrument(2885,"RELIANCE","NC");
    private final Instant now = LocalDate.of(2026,10,29).atTime(23,0).atZone(StockAnalysisService.IST).toInstant();
    private JSONObject identity(String exchange, String token, String symbol) {
        return new JSONObject().put("stat","Ok").put("exch",exchange).put("token",token).put("tsym",symbol);
    }
    private JSONObject row(boolean intraday) {
        var at = LocalDate.of(2026,10,28).atTime(9,15);
        return new JSONObject().put("time",intraday?"28-10-2026 09:15:00":"28-OCT-2026")
                .put("ssboe",""+(intraday?at.atZone(StockAnalysisService.IST).toEpochSecond():at.toLocalDate().atStartOfDay(ZoneOffset.UTC).toEpochSecond()))
                .put("into","100.00").put("inth","103.00").put("intl","99.00").put("intc","102.00").put("intv","1234.00");
    }
    @Test void parsesLiveDailyEncodingAndDecimalIntegerVolume() {
        var bars = StockAnalysisShoonyaFeed.parse(new JSONArray().put(row(false).toString()).toString(),false);
        assertThat(bars).hasSize(1); assertThat(bars.getFirst().volume()).isEqualTo(1234);
        assertThat(bars.getFirst().date()).isEqualTo(LocalDate.of(2026,10,28));
    }
    @Test void checksIntradayEpochAgainstIstTimestamp() {
        var raw=row(true); assertThat(StockAnalysisShoonyaFeed.parse(new JSONArray().put(raw).toString(),true)).hasSize(1);
        raw.put("ssboe",String.valueOf(Long.parseLong(raw.getString("ssboe"))+60));
        assertThatThrownBy(()->StockAnalysisShoonyaFeed.parse(new JSONArray().put(raw).toString(),true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsErrorObjectAndFractionalVolumeRatherThanSilentlyDroppingRows() {
        assertThatThrownBy(()->StockAnalysisShoonyaFeed.parse("{\"stat\":\"Not_Ok\"}",true)).isInstanceOf(JSONException.class);
        var raw=row(false).put("intv","12.5");
        assertThatThrownBy(()->StockAnalysisShoonyaFeed.parse(new JSONArray().put(raw).toString(),false)).isInstanceOf(ArithmeticException.class);
    }
    @Test void invalidPricesRemainVisibleToQualityGate() {
        var raw=row(false).put("intc",98);
        var bars=StockAnalysisShoonyaFeed.parse(new JSONArray().put(raw).toString(),false);
        var report=StockAnalysisService.calculate(nse,"SWING",bars,now,"Shoonya",List.of());
        assertThat(report.rejected()).isOne(); assertThat(report.indicators().rsi14()).isNull();
    }
    @Test void verifiesExactCashIdentityOnBothExchanges() {
        assertThat(StockAnalysisShoonyaFeed.verifiedSymbol(identity("NSE","2885","RELIANCE-EQ"),nse)).isEqualTo("RELIANCE-EQ");
        var bse=new StockAnalysisService.Instrument(500325,"RELIANCE","BC");
        assertThat(StockAnalysisShoonyaFeed.verifiedSymbol(identity("BSE","500325","RELIANCE"),bse)).isEqualTo("RELIANCE");
        assertThatThrownBy(()->StockAnalysisShoonyaFeed.verifiedSymbol(identity("BSE","2885","RELIANCE"),nse)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->StockAnalysisShoonyaFeed.verifiedSymbol(identity("NSE","2885","OTHER-EQ"),nse)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->StockAnalysisShoonyaFeed.verifiedSymbol(identity("NSE","999","RELIANCE-EQ"),nse)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void customerSessionIsNeverUsedForSharedResearch() {
        var properties=new ShoonyaProperties(); var tokens=mock(AccessTokenRepository.class); var crypto=mock(CryptoService.class);
        var feed=spy(new StockAnalysisShoonyaFeed(properties,tokens,crypto));
        when(tokens.findAllByBrokerName("Shoonya")).thenReturn(List.of(AccessTokenEntity.builder().token("private").expiry(now.plusSeconds(3600)).userId(42L).build()));
        assertThat(feed.fetch(nse,"SWING",now)).isEmpty();verifyNoInteractions(crypto);
    }
    @Test void savedCurrentDayGlobalSessionWorksWithoutLoginOrChangingExpiry() throws Exception {
        var properties=new ShoonyaProperties(); var tokens=mock(AccessTokenRepository.class); var crypto=mock(CryptoService.class);
        var feed=spy(new StockAnalysisShoonyaFeed(properties,tokens,crypto));
        var expiry=LocalDate.of(2026,10,29).atTime(16,40).atZone(StockAnalysisService.IST).toInstant();
        var saved=AccessTokenEntity.builder().token("encrypted").expiry(expiry).build();
        when(tokens.findAllByBrokerName("Shoonya")).thenReturn(List.of(saved));when(crypto.decrypt("encrypted")).thenReturn("session");
        doReturn(identity("NSE","2885","RELIANCE-EQ").toString()).when(feed).post(eq("GetSecurityInfo"),any(),eq("session"));
        doReturn(new JSONArray().put(row(false).toString()).toString()).when(feed).post(eq("EODChartData"),any(),eq("session"));
        assertThat(feed.fetch(nse,"SWING",now)).hasSize(1);assertThat(saved.getExpiry()).isEqualTo(expiry);
        verify(tokens,never()).save(any());
    }
}
