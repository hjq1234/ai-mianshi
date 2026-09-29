package com.ke.nhservice.aimianshi.wrapper.asr;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsrConfig {

    @Bean
    public AsrClient asrClient(AsrProperties props) {
        return new SherpaAsrClient(props);
    }
}