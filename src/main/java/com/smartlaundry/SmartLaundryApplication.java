package com.smartlaundry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point. The web server stays alive for the whole session; simulations are started/stopped inside it. */
@SpringBootApplication
public class SmartLaundryApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmartLaundryApplication.class, args);
    }
}
