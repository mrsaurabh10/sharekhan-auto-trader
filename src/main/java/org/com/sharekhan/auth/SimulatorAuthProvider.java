package org.com.sharekhan.auth;

import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.enums.Broker;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class SimulatorAuthProvider implements BrokerAuthProvider {

    @Override
    public Broker getBroker() {
        return Broker.SIMULATOR;
    }

    @Override
    public AuthTokenResult loginAndFetchToken() {
        // Always return a dummy token that is valid
        return new AuthTokenResult("SIMULATOR_TOKEN_" + System.currentTimeMillis(), 86400L);
    }

    @Override
    public AuthTokenResult loginAndFetchToken(BrokerCredentialsEntity creds) {
        // Always return a dummy token that is valid
        return new AuthTokenResult("SIMULATOR_TOKEN_" + creds.getCustomerId() + "_" + System.currentTimeMillis(), 86400L);
    }
}
