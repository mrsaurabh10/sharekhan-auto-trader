package org.com.sharekhan.auth;

import org.com.sharekhan.enums.Broker;

public interface BrokerAuthProvider {
    Broker getBroker();
    AuthTokenResult loginAndFetchToken();
}

