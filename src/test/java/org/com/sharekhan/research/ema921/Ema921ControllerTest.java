package org.com.sharekhan.research.ema921;
import org.com.sharekhan.config.SecurityConfig;
import org.com.sharekhan.service.AdminUserDetailsService;
import org.com.sharekhan.service.CurrentUserService;
import org.com.sharekhan.onboarding.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(Ema921Controller.class) @Import(SecurityConfig.class)
class Ema921ControllerTest {
 @Autowired MockMvc mvc;
 @MockBean AdminUserDetailsService users;
 @MockBean CurrentUserService current;
 @MockBean UserTradingAccess access;
 MockMultipartFile file(){return new MockMultipartFile("spot","s.csv","text/csv","bad".getBytes());}
 @Test void authenticationAndCsrfRequired()throws Exception{
  mvc.perform(multipart("/api/strategy-research/ema921").file(file()).param("config","{}").with(csrf())).andExpect(status().isUnauthorized());
  mvc.perform(multipart("/api/strategy-research/ema921").file(file()).param("config","{}").with(user("a").roles("USER"))).andExpect(status().isForbidden());
 }
 @Test void invalidRequestIs400AndNoStore()throws Exception{
  for(String cfg:new String[]{"{}","null","{\"timeframe\":5,\"start\":\"09:30\",\"cutoff\":\"14:30\",\"slippageRate\":0.005,\"fixedPoints\":0,\"fees\":[{\"from\":\"2026-01-01\",\"to\":\"2026-01-31\",\"source\":\"missing rates\"}]}"})
   mvc.perform(multipart("/api/strategy-research/ema921").file(file()).param("config",cfg).with(csrf()).with(user("a").roles("USER"))).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"));
 }
 @Test void rolesProtectPage()throws Exception{
  mvc.perform(get("/strategy-research/ema921").with(user("a").roles("OTHER"))).andExpect(status().isForbidden());
  mvc.perform(get("/strategy-research/ema921").with(user("a").roles("USER"))).andExpect(status().isOk()).andExpect(view().name("ema921"));
 }
 @Test void successfulResearchPostReturnsReportWithoutCaching()throws Exception{
  StringBuilder csv=new StringBuilder("timestamp,open,high,low,close\n");
  for(var b:Ema921EngineTest.bars())csv.append(b.at()).append(",").append(b.open()).append(",").append(b.high()).append(",").append(b.low()).append(",").append(b.close()).append("\n");
  var file=new MockMultipartFile("spot","s.csv","text/csv",csv.toString().getBytes());
  String config="{\"timeframe\":5,\"start\":\"09:30\",\"cutoff\":\"14:30\",\"slippageRate\":0.005,\"fixedPoints\":0,\"fees\":[]}";
  mvc.perform(multipart("/api/strategy-research/ema921").file(file).param("config",config).with(csrf()).with(user("a").roles("USER")))
   .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.evidenceStatus").value("USER_SUPPLIED_UNVERIFIED")).andExpect(jsonPath("$.trades[0].status").value("UNPRICED"));
 }
}
