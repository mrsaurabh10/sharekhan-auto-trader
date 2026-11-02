package org.com.sharekhan.analysis;
import org.junit.jupiter.api.Test;import org.com.sharekhan.repository.*;import org.com.sharekhan.entity.*;import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;import java.util.*;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
class ThesisOwnerTest {
 @Test void stableOwnerUsesCanonicalAccountFamilyNotClientOrRoleLabel(){var admins=mock(AdminUserRepository.class);var users=mock(AppUserRepository.class);var owner=new ThesisOwner(admins,users);when(admins.findByUsername("same")).thenReturn(Optional.of(new AdminUser(10L,"same","unused","ROLE_ADMIN")));var auth=new UsernamePasswordAuthenticationToken("same","unused",List.of());assertThat(owner.resolve(auth)).isEqualTo("ADMIN:10");verifyNoInteractions(users);}
 @Test void ordinaryUserAndAdminIdsNeverCollide(){var admins=mock(AdminUserRepository.class);var users=mock(AppUserRepository.class);when(users.findByUsername("user")).thenReturn(Optional.of(new AppUser(10L,"user",null,null,null)));assertThat(new ThesisOwner(admins,users).resolve(new UsernamePasswordAuthenticationToken("user","unused",List.of()))).isEqualTo("USER:10");}
 @Test void missingOrUnauthenticatedIdentityFailsClosed(){var owner=new ThesisOwner(mock(AdminUserRepository.class),mock(AppUserRepository.class));assertThatThrownBy(()->owner.resolve(null)).hasMessageContaining("401");assertThatThrownBy(()->owner.resolve(new UsernamePasswordAuthenticationToken("unknown","unused",List.of()))).hasMessageContaining("401");}
}
