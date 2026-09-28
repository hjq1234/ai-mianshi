package com.ke.nhservice.aimianshi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;

@SpringBootApplication
public class AiMianshiApplication {

    public static void main(String[] args) {
        // SQLite 不会自动创建父目录，Datasource 初始化前得先把它建出来
        new File("data").mkdirs();
        SpringApplication.run(AiMianshiApplication.class, args);
    }
}