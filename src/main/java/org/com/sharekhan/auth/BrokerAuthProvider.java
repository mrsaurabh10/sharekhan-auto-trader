package org.com.sharekhan.auth;

import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.enums.Broker;

public interface BrokerAuthProvider {
    Broker getBroker();
    AuthTokenResult loginAndFetchToken();

    // default overload to support provider login using per-customer credentials
    default AuthTokenResult loginAndFetchToken(BrokerCredentialsEntity creds) {
        return loginAndFetchToken();
    }
}
