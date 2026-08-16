package org.com.sharekhan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.com.sharekhan.config.MarketauxProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MarketauxNewsServiceTest {

    @Test
    void requestsOnlyIndiaNewsAndDoesNotExposeToken() {
        MarketauxProperties properties = configuredProperties();
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), requestTo("https://example.test/v1/news/all?api_token=test-token&countries=in&language=en&filter_entities=true&limit=50&page=1&symbols=RELIANCE.NS,TCS.NS&published_after=2026-08-01"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"meta\":{\"found\":1},\"data\":[{\"title\":\"India news\"}]}", MediaType.APPLICATION_JSON));

        MarketauxNewsService service = new MarketauxNewsService(properties, restTemplate, new ObjectMapper());
        Map<String, Object> result = service.indiaNews(List.of("RELIANCE.NS", "TCS.NS"), null, "2026-08-01", null, 99, 0);

        assertEquals("IN", result.get("country"));
        assertFalse(result.toString().contains("test-token"));
        server.verify();
    }

    @Test
    void rejectsInvalidDatesBeforeCallingProvider() {
        MarketauxNewsService service = new MarketauxNewsService(configuredProperties(), new RestTemplate(), new ObjectMapper());
        assertThrows(IllegalArgumentException.class,
                () -> service.indiaNews(null, null, "01-08-2026", null, 20, 1));
    }

    @Test
    void extractsOnlyEntitySentimentFieldsForScheduledCollection() {
        MarketauxProperties properties = configuredProperties();
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), requestTo("https://example.test/v1/news/all?api_token=test-token&countries=in&language=en&filter_entities=true&limit=20&page=1"))
                .andRespond(withSuccess("{\"meta\":{},\"data\":[{\"uuid\":\"article-1\",\"published_at\":\"2026-08-16T09:20:00Z\",\"entities\":[{\"symbol\":\"^NSEI\",\"name\":\"Nifty 50\",\"sentiment_score\":0.82}]}]}", MediaType.APPLICATION_JSON));

        MarketauxNewsService service = new MarketauxNewsService(properties, restTemplate, new ObjectMapper());
        List<MarketauxNewsService.IndiaEntitySentiment> sentiments = service.latestIndiaEntitySentiments(20);

        assertEquals(1, sentiments.size());
        assertEquals("^NSEI", sentiments.get(0).entitySymbol());
        assertEquals(0.82, sentiments.get(0).sentimentScore());
        server.verify();
    }

    private MarketauxProperties configuredProperties() {
        MarketauxProperties properties = new MarketauxProperties();
        properties.setApiToken("test-token");
        properties.setBaseUrl("https://example.test/v1");
        return properties;
    }
}
