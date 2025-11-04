package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "broker_credentials")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrokerCredentialsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "broker_name", nullable = false)
    private String brokerName;

    @Column(name = "customer_id", nullable = true)
    private Long customerId;

    @Column(name = "api_key", length = 1024)
    private String apiKey;

    @Column(name = "broker_username", length = 512)
    private String brokerUsername;

    @Column(name = "broker_password", length = 1024)
    private String brokerPassword;

    @Column(name = "client_code", length = 256)
    private String clientCode;

    @Column(name = "totp_secret", length = 1024)
    private String totpSecret;

    @Column(name = "secret_key", length = 1024)
    private String secretKey;

}
