package org.com.sharekhan.analysis;
import org.springframework.stereotype.Component;import org.springframework.security.core.Authentication;import org.springframework.transaction.annotation.Transactional;
import org.com.sharekhan.repository.*;import org.springframework.web.server.ResponseStatusException;import org.springframework.http.HttpStatus;
/** Resolve the canonical account family and stable ID; never accept an owner from client input. */
@Component
public class ThesisOwner {
 private final AdminUserRepository admins;private final AppUserRepository users;
 public ThesisOwner(AdminUserRepository admins,AppUserRepository users){this.admins=admins;this.users=users;}
 @Transactional(readOnly=true)
 public String resolve(Authentication authentication){
  if(authentication==null||!authentication.isAuthenticated()||authentication.getName()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in again");
  String name=authentication.getName();var admin=admins.findByUsername(name);if(admin.isPresent()&&admin.get().getId()!=null)return "ADMIN:"+admin.get().getId();
  var user=users.findByUsername(name).or(()->users.findByUsername(name.trim().toLowerCase(java.util.Locale.ROOT)));
  if(user.isPresent()&&user.get().getId()!=null)return "USER:"+user.get().getId();throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Account identity unavailable; sign in again");
 }
}
