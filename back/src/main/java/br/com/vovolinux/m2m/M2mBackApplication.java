package br.com.vovolinux.m2m;

import br.com.vovolinux.m2m.config.AppProperties;
import br.com.vovolinux.m2m.config.LocalProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({AppProperties.class, LocalProperties.class})
public class M2mBackApplication {

    public static void main(String[] args) {
        SpringApplication.run(M2mBackApplication.class, args);
    }
}
