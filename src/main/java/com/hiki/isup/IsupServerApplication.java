package com.hiki.isup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 海康 ISUP 5.0 接入服务启动类。
 */
@SpringBootApplication
public class IsupServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(IsupServerApplication.class, args);
    }
}
