package org.com.sharekhan.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.com.sharekhan.auth.*;
import org.com.sharekhan.config.SecurityConfig;
import org.com.sharekhan.controller.*;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.util.CryptoService;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Full HTTP/security/JPA onboarding flow; external mail and broker execution stay stubbed. No schedulers run. */
@SpringBootTest(classes=BackendHttpIntegrationTest.Backend.class, properties={
    "app.onboarding.enabled=true", "spring.datasource.url=jdbc:h2:mem:backend-http;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.open-in-view=false"})
@AutoConfigureMockMvc
@ActiveProfiles("backend-test")
class BackendHttpIntegrationTest {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    @EntityScan(basePackages={"org.com.sharekhan.entity","org.com.sharekhan.onboarding"})
    @EnableJpaRepositories(basePackages={"org.com.sharekhan.repository","org.com.sharekhan.onboarding"})
    @Import({SecurityConfig.class, AccountController.class, OnboardingController.class, BrokerCatalogController.class,
        DhanConnectionController.class, UserController.class, AuthController.class, LoginController.class, PortalController.class,
        StrategyController.class, TradeTriggerController.class, TradeRequestController.class, OrderViewController.class,
        TradeAnalyticsController.class, AccountService.class, CurrentUserService.class, AdminUserDetailsService.class,
        UserConfigService.class, UserTradingAccess.class, BrokerConnectionService.class, BrokerCredentialsService.class,
        OnboardingStatusService.class, CryptoService.class, AccessTokenPersistenceService.class, PortalExceptionHandler.class,
        StrategyTemplateService.class, StrategySubscriptionService.class, StrategyEnrollmentService.class, NseMarketCalendar.class,
        TradingRequestService.class, TradeCostCalculator.class, TradeAnalyticsService.class})
    static class Backend {
        @Bean StrategyEvaluator stockStrategy() {
            return new StrategyEvaluator() {
                public StrategyMetadata metadata(){return new StrategyMetadata("STOCK_0925_MOVER_ATR_BREAKOUT","Stocks 9:25 Mover Breakout","Stock test evaluator", "");}
                public StrategyApplyResponse apply(StrategyApplyRequest r){return StrategyApplyResponse.builder().status("waiting").message("Waiting").build();}
            };
        }
        @Bean StrategyEvaluator testStrategy() {
            return new StrategyEvaluator() {
                public StrategyMetadata metadata(){return new StrategyMetadata("TEST_BREAKOUT","Test breakout","Integration test strategy", "CE");}
                public StrategyApplyResponse apply(StrategyApplyRequest r){return StrategyApplyResponse.builder().status("waiting").message("Waiting").build();}
            };
        }
    }
    @DynamicPropertySource static void cryptoKey(DynamicPropertyRegistry properties) {
        byte[] random = new byte[32]; new java.security.SecureRandom().nextBytes(random);
        String key=java.util.Base64.getEncoder().encodeToString(random);
        properties.add("app.crypto.key",()->key);
    }
    @Autowired MockMvc mvc;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    private static final java.util.concurrent.atomic.AtomicInteger PEER = new java.util.concurrent.atomic.AtomicInteger();
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired AccountProfileRepository profiles;
    @Autowired BrokerCredentialsRepository brokers;
    @Autowired BrokerConnectionRepository connections;
    @Autowired AccessTokenRepository tokens;
    @Autowired StrategySubscriptionRepository subscriptions;
    @Autowired TriggerTradeRequestRepository requests;
    @Autowired TriggeredTradeSetupRepository trades;
    @Autowired CryptoService crypto;
    @Autowired StrategyEnrollmentService enrollment;
    @MockBean AccountMailService mail;
    @MockBean SharekhanAuthProvider provider;
    @MockBean DhanAuthProvider dhan;
    @MockBean TradeExecutionService engine;
    @MockBean TradeCloseService close;
    @MockBean TradingMessageService messages;
    @MockBean StockAtrTradeService stockAtr;
    @MockBean WebSocketSubscriptionHelper webSocket;
    @MockBean AtrPreviousDayTradeLevelService atr;
    @MockBean GeminiTradeInsightService insights;

    @BeforeEach void setup() {
        String peer="192.0.2."+PEER.incrementAndGet();
        mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
            .defaultRequest(get("/").with(request->{request.setRemoteAddr(peer);return request;})).build();
        trades.deleteAll(); requests.deleteAll(); subscriptions.deleteAll(); tokens.deleteAll();
        connections.deleteAll(); brokers.deleteAll(); profiles.deleteAll(); users.deleteAll();
        when(mail.configured()).thenReturn(true);
        when(provider.loginAndFetchToken(any(BrokerCredentialsEntity.class))).thenReturn(new AuthTokenResult("test-token-not-live",3600));
        when(engine.executeTrade(any(TriggerRequest.class))).thenAnswer(call->{
            TriggerRequest input=call.getArgument(0);
            return requests.saveAndFlush(TriggerTradeRequestEntity.builder().appUserId(input.getUserId())
                .brokerCredentialsId(input.getBrokerCredentialsId()).symbol(input.getInstrument()).exchange(input.getExchange())
                .entryPrice(input.getEntryPrice()).stopLoss(input.getStopLoss()).quantity(input.getQuantity().longValue())
                .source(input.getSource()).createdAt(LocalDateTime.now()).status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION).build());
        });
    }
    @Test void dhanTotpAndApiKeyFlowsPersistScopedEncryptedTokensAndRequireTradingConsent() throws Exception {
        MockHttpSession session=registerAndLogin();
        when(dhan.loginAndFetchToken(any(BrokerCredentialsEntity.class))).thenReturn(new AuthTokenResult("dhan-test-token",3600));
        when(dhan.beginConsent(any())).thenReturn("https://auth.dhan.co/login/consentApp-login?consentAppId=test-consent");
        when(dhan.consumeConsent(any(),eq("test-token-id"))).thenReturn(new AuthTokenResult("dhan-consent-token",3600));
        String payload="{\"brokerName\":\"Dhan\",\"clientCode\":\"1000000001\",\"brokerPassword\":\"012345\",\"totpSecret\":\"JBSWY3DPEHPK3PXP\",\"apiKey\":\"test-key\",\"secretKey\":\"test-secret\"}";
        mvc.perform(post("/api/user/brokers").session(session).contentType("application/json").content(payload)).andExpect(status().isForbidden());
        String response=mvc.perform(post("/api/user/brokers").session(session).with(csrf()).contentType("application/json").content(payload))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id=json.readTree(response).get("id").asLong();
        var saved=brokers.findById(id).orElseThrow();
        assertThat(saved.getBrokerName()).isEqualTo("Dhan");
        assertThat(crypto.decrypt(saved.getBrokerPassword())).isEqualTo("012345");
        assertThat(saved.getTotpSecret()).isNotEqualTo("JBSWY3DPEHPK3PXP");
        mvc.perform(post("/api/user/brokers/"+id+"/connect").session(session).with(csrf())).andExpect(status().isOk());
        var token=tokens.findAllByBrokerNameAndBrokerCredentialsId("Dhan",id).getFirst();
        assertThat(crypto.decrypt(token.getToken())).isEqualTo("dhan-test-token");
        mvc.perform(get("/api/user/onboarding").session(session)).andExpect(jsonPath("$.connectedBrokers").value(1))
            .andExpect(jsonPath("$.tradingReadyBrokers").value(0));
        mvc.perform(get("/api/user/brokers").session(session)).andExpect(jsonPath("$[0].tradingSupported").value(true))
            .andExpect(jsonPath("$[0].apiKey").doesNotExist()).andExpect(jsonPath("$[0].totpSecret").doesNotExist());
        mvc.perform(post("/api/user/brokers/"+id+"/trading").session(session).with(csrf()).contentType("application/json").content("{\"enabled\":true}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/user/onboarding").session(session)).andExpect(jsonPath("$.tradingReadyBrokers").value(1));
        mvc.perform(post("/api/user/brokers/"+id+"/trading").session(session).with(csrf()).contentType("application/json").content("{\"enabled\":false}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/strategies/start").session(session).with(csrf()).contentType("application/json")
            .content("{\"templateId\":\"TEST_BREAKOUT\",\"symbol\":\"NIFTY\",\"lots\":1,\"brokerCredentialsId\":"+id+"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/user/brokers/dhan/callback?tokenId=test-token-id").session(session))
            .andExpect(status().isSeeOther()).andExpect(header().string("Location","/portal?dhan=failed#brokers"));
        verify(dhan,never()).consumeConsent(any(),any());
        mvc.perform(post("/api/user/brokers/"+id+"/dhan/consent").session(session).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.loginUrl").value("https://auth.dhan.co/login/consentApp-login?consentAppId=test-consent"));
        mvc.perform(get("/api/user/brokers/dhan/callback?tokenId=test-token-id").session(session))
            .andExpect(status().isSeeOther()).andExpect(header().string("Location","/portal?dhan=connected#brokers"))
            .andExpect(header().string("Cache-Control","no-store"));
        assertThat(crypto.decrypt(tokens.findAllByBrokerNameAndBrokerCredentialsId("Dhan",id).getFirst().getToken())).isEqualTo("dhan-consent-token");
        mvc.perform(get("/api/user/brokers/dhan/callback?tokenId=test-token-id").session(session))
            .andExpect(header().string("Location","/portal?dhan=failed#brokers"));
        verify(dhan,times(1)).consumeConsent(any(),eq("test-token-id"));
        mvc.perform(post("/api/user/brokers/"+id+"/dhan/consent").with(user("unrelated-user")).with(csrf()))
            .andExpect(status().isForbidden());
        // A callback must not use credentials edited after consent was initiated.
        mvc.perform(post("/api/user/brokers/"+id+"/dhan/consent").session(session).with(csrf())).andExpect(status().isOk());
        mvc.perform(put("/api/user/brokers/"+id).session(session).with(csrf()).contentType("application/json")
            .content("{\"apiKey\":\"changed-key\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/user/brokers/dhan/callback?tokenId=test-token-id").session(session))
            .andExpect(header().string("Location","/portal?dhan=failed#brokers"));
        verify(dhan,times(1)).consumeConsent(any(),eq("test-token-id"));
        verifyNoInteractions(engine);
    }
    @Test void dhanVerifiedPinOnlyAccountCanSubmitScopedRequestsAndStrategiesAfterConsent() throws Exception {
        MockHttpSession session=registerAndLogin();
        when(dhan.loginAndFetchToken(any(BrokerCredentialsEntity.class))).thenReturn(new AuthTokenResult("dhan-test-token",3600));
        String payload="{\"brokerName\":\"Dhan\",\"clientCode\":\"1000000001\",\"brokerPassword\":\"012345\",\"totpSecret\":\"JBSWY3DPEHPK3PXP\"}";
        String created=mvc.perform(post("/api/user/brokers").session(session).with(csrf()).contentType("application/json").content(payload))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id=json.readTree(created).get("id").asLong();
        mvc.perform(post("/api/trades/trigger-on-price").session(session).with(csrf()).contentType("application/json").content(orderJson(id))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/user/brokers/"+id+"/connect").session(session).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/trades/trigger-on-price").session(session).with(csrf()).contentType("application/json").content(orderJson(id))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/user/brokers/"+id+"/trading").session(session).with(csrf()).contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isOk());
        mvc.perform(post("/api/trades/trigger-on-price").session(session).with(csrf()).contentType("application/json").content(orderJson(id))).andExpect(status().isOk())
            .andExpect(jsonPath("$.brokerCredentialsId").value(id));
        mvc.perform(post("/api/strategies/start").session(session).with(csrf()).contentType("application/json")
            .content("{\"templateId\":\"TEST_BREAKOUT\",\"symbol\":\"NIFTY\",\"lots\":1,\"brokerCredentialsId\":"+id+"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(brokers.findById(id).orElseThrow().getApiKey()).isNull();
        assertThat(brokers.findById(id).orElseThrow().getCustomerId()).isNull();
    }
    @Test void signupBrokerSubscriptionAndOrderWorkThroughAuthenticatedHttp() throws Exception {
        MockHttpSession session=registerAndLogin();
        mvc.perform(get("/api/brokers").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("Sharekhan")).andExpect(jsonPath("$[0].requiredFields").isArray());
        mvc.perform(get("/api/user/onboarding").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.nextStep").value("BROKER"));
        long id=createBroker(session);
        var saved=brokers.findById(id).orElseThrow();
        assertThat(saved.getBrokerPassword()).isNotEqualTo("broker-test-password");
        assertThat(crypto.decrypt(saved.getBrokerPassword())).isEqualTo("broker-test-password");
        assertThat(saved.getActive()).isFalse();assertThat(saved.getTradingEnabled()).isFalse();
        mvc.perform(get("/api/user/brokers/"+id).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.brokerPassword").doesNotExist()).andExpect(jsonPath("$.secretKey").doesNotExist());
        mvc.perform(post("/api/user/brokers/"+id+"/trading").session(session).with(csrf())
            .contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/user/brokers/"+id+"/connect").session(session).with(csrf())).andExpect(status().isOk());
        assertThat(tokens.findAllByBrokerNameAndBrokerCredentialsId("Sharekhan",id)).hasSize(1);
        assertThat(tokens.findAllByBrokerNameAndBrokerCredentialsId("Sharekhan",id).getFirst().getToken()).isNotEqualTo("test-token-not-live");
        mvc.perform(get("/api/user/onboarding").session(session)).andExpect(jsonPath("$.nextStep").value("TRADING"));
        mvc.perform(post("/api/user/brokers/"+id+"/trading").session(session).with(csrf())
            .contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isOk());
        mvc.perform(post("/api/strategies/start").session(session).with(csrf()).contentType("application/json")
            .content("{\"templateId\":\"TEST_BREAKOUT\",\"symbol\":\"NIFTY\",\"lots\":1,\"userId\":999,\"brokerCredentialsId\":"+id+"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(post("/api/strategies/start").session(session).with(csrf()).contentType("application/json")
            .content("{\"templateId\":\"TEST_BREAKOUT\",\"symbol\":\"NIFTY\",\"lots\":1,\"brokerCredentialsId\":"+id+"}"))
            .andExpect(status().isOk());
        assertThat(subscriptions.count()).isOne();
        long owner=users.findByUsername("alex@example.test").orElseThrow().getId();
        assertThat(subscriptions.findAll().getFirst().getAppUserId()).isEqualTo(owner);
        mvc.perform(post("/api/trades/trigger-on-price").session(session).with(csrf()).contentType("application/json")
            .content(orderJson(id))).andExpect(status().isOk()).andExpect(jsonPath("$.appUserId").value(owner));
        mvc.perform(get("/api/orders/requests?userId=999&scope=simulator&page=0&size=10").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].appUserId").value(owner));
        mvc.perform(get("/api/user/onboarding").session(session)).andExpect(jsonPath("$.nextStep").value("COMPLETE"));
        mvc.perform(post("/api/user/brokers/"+id+"/disconnect").session(session).with(csrf())).andExpect(status().isConflict());
    }
    @Test void invalidBrokerDataAndForeignBrokerCannotReachExecution() throws Exception {
        MockHttpSession session=registerAndLogin();
        mvc.perform(post("/api/user/brokers").session(session).with(csrf()).contentType("application/json")
            .content("{\"brokerName\":\"Sharekhan\",\"customerId\":-1}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/user/brokers").session(session).with(csrf()).contentType("application/json")
            .content(credentialsJson().replace("Sharekhan","UnknownBroker"))).andExpect(status().isBadRequest());
        long id=createBroker(session);
        mvc.perform(post("/api/trades/trigger-on-price").session(session).with(csrf()).contentType("application/json")
            .content(orderJson(id))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/trades/trigger-on-price").session(session).with(csrf()).contentType("application/json")
            .content(orderJson(id+999))).andExpect(status().isForbidden());
        verify(engine,never()).executeTrade(any(TriggerRequest.class));
    }
    @Test void fractionalAndOversizedBrokerInputsAreRejectedBeforeSaving() throws Exception {
        MockHttpSession session=registerAndLogin();
        mvc.perform(post("/api/user/brokers").session(session).with(csrf()).contentType("application/json")
            .content(credentialsJson().replace("12345","1.5"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/user/brokers").session(session).with(csrf()).contentType("application/json")
            .content(credentialsJson().replace("broker-test-password","€".repeat(200)))).andExpect(status().isBadRequest());
        assertThat(brokers.count()).isZero();
    }
    @Test void personalAnalyticsExcludesAnotherUsersTradesEvenWithSpoofedScope() throws Exception {
        MockHttpSession session=registerAndLogin();
        long owner=users.findByUsername("alex@example.test").orElseThrow().getId();
        trades.saveAndFlush(closedTrade(owner,"OWN",100.0));trades.saveAndFlush(closedTrade(owner+999,"FOREIGN",9000.0));
        mvc.perform(get("/api/analytics/trades?userId="+(owner+999)+"&scope=all").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summary.realizedPnl").value(100.0))
            .andExpect(jsonPath("$.summary.totalClosedTrades").value(1));
    }
    @Test void unknownStrategyDoesNotCreateBackgroundSubscription() throws Exception {
        MockHttpSession session=registerAndLogin(); long id=createBroker(session);
        mvc.perform(post("/api/user/brokers/"+id+"/connect").session(session).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/user/brokers/"+id+"/trading").session(session).with(csrf()).contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isOk());
        mvc.perform(post("/api/strategies/start").session(session).with(csrf()).contentType("application/json")
            .content("{\"templateId\":\"UNKNOWN\",\"symbol\":\"NIFTY\",\"lots\":1,\"brokerCredentialsId\":"+id+"}"))
            .andExpect(status().isBadRequest());
        assertThat(subscriptions.count()).isZero();
    }
    @Test void concurrentSubscriptionEnrollmentCreatesOneRow() throws Exception {
        var user = new AppUser();user.setUsername("enrollment@example.test");user.setPassword("test-hash");users.saveAndFlush(user);
        var request = new StrategyApplyRequest();request.setUserId(user.getId());request.setBrokerCredentialsId(1L);request.setLots(1);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Long> task = () -> {
                ready.countDown();start.await();
                return enrollment.enroll(request,"TEST_BREAKOUT","NIFTY","strategy:TEST_BREAKOUT").getId();
            };
            var first = pool.submit(task);var second = pool.submit(task);
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(10,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(subscriptions.count()).isOne();
    }


    @Test void stockSubscriptionPersistsBudgetThroughScopedHttpAndUpdatesWithoutAnotherEnrollment() throws Exception {
        var session = registerAndLogin(); long broker = createBroker(session);
        mvc.perform(post("/api/user/brokers/"+broker+"/connect").session(session).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/user/brokers/"+broker+"/trading").session(session).with(csrf())
                .contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isOk());
        var body = new java.util.HashMap<String,Object>();
        body.put("templateId", "STOCK_0925_MOVER_ATR_BREAKOUT"); body.put("userId", 999);
        body.put("brokerCredentialsId", broker); body.put("investmentPerStock", new java.math.BigDecimal("10000.50"));
        mvc.perform(post("/api/strategies/start").session(session).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(body))).andExpect(status().isOk())
                .andExpect(jsonPath("$.investmentPerStock").value(10000.50)).andExpect(jsonPath("$.symbol").value("FNO_UNIVERSE"));
        var first = subscriptions.findAll().getFirst();
        assertThat(first.getAppUserId()).isNotEqualTo(999L); assertThat(first.getLots()).isNull();
        assertThat(first.getInvestmentPerStock()).isEqualByComparingTo("10000.50");
        body.put("investmentPerStock", new java.math.BigDecimal("20000"));
        mvc.perform(post("/api/strategies/start").session(session).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(body))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(first.getId()));
        assertThat(subscriptions.count()).isOne();
        assertThat(subscriptions.findById(first.getId()).orElseThrow().getInvestmentPerStock()).isEqualByComparingTo("20000");
        body.put("investmentPerStock", 0);
        mvc.perform(post("/api/strategies/start").session(session).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        body.remove("investmentPerStock");
        mvc.perform(post("/api/strategies/start").session(session).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        verify(engine, never()).executeTriggeredTrade(any());
    }

    private MockHttpSession registerAndLogin() throws Exception {
        mvc.perform(post("/api/account/register").with(csrf()).contentType("application/json")
            .content("{\"displayName\":\"Alex\",\"email\":\"alex@example.test\",\"password\":\"a unique test password\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/login").with(csrf()).param("username","alex@example.test").param("password","a unique test password"))
            .andExpect(redirectedUrl("/login?error"));
        var token=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("alex@example.test"),token.capture(),eq("VERIFY"));
        mvc.perform(post("/api/account/verify").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(new AccountService.TokenRequest(token.getValue(),null))))
            .andExpect(status().isOk());
        var result=mvc.perform(post("/login").with(csrf()).param("username","ALEX@EXAMPLE.TEST").param("password","a unique test password"))
            .andExpect(redirectedUrl("/dashboard")).andReturn();
        return (MockHttpSession)result.getRequest().getSession(false);
    }
    private long createBroker(MockHttpSession session) throws Exception {
        var result=mvc.perform(post("/api/user/brokers").session(session).with(csrf()).contentType("application/json").content(credentialsJson()))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
    private String credentialsJson(){return "{\"brokerName\":\"Sharekhan\",\"customerId\":12345,\"clientCode\":\"TESTCLIENT\",\"apiKey\":\"test-api-key\",\"brokerPassword\":\"broker-test-password\",\"totpSecret\":\"test-totp\",\"secretKey\":\"test-secret\",\"appUserId\":999,\"tradingEnabled\":true}";}
    private String orderJson(long id){return "{\"brokerCredentialsId\":"+id+",\"userId\":999,\"instrument\":\"RELIANCE\",\"exchange\":\"NC\",\"quantity\":1,\"lots\":999,\"quickTrade\":true,\"entryPrice\":100,\"stopLoss\":90,\"target1\":120}";}
    private TriggeredTradeSetupEntity closedTrade(long owner,String symbol,double pnl) {
        return TriggeredTradeSetupEntity.builder().appUserId(owner).symbol(symbol).status(TriggeredTradeStatus.EXITED_SUCCESS)
            .quantity(10L).actualEntryPrice(100.0).exitPrice(110.0).pnl(pnl).triggeredAt(LocalDateTime.now())
            .entryAt(LocalDateTime.now()).exitedAt(LocalDateTime.now()).build();
    }
}
