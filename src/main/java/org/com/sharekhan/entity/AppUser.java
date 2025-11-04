package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_user")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    // Optional mapping to external customer id used in trading requests
    private Long customerId;

    // Encrypted fields (stored as base64 ciphertext)
    @Column(name = "broker_api_key")
    private String brokerApiKey; // encrypted

    @Column(name = "broker_username")
    private String brokerUsername; // encrypted

    @Column(name = "broker_password")
    private String brokerPassword; // encrypted

    private String notes;
}

