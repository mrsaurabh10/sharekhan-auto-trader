package org.com.sharekhan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SharekhanAlgoApp {
    public static void main(String[] args) {
        SpringApplication.run(SharekhanAlgoApp.class, args);
    }
}
