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
        // "decrypt" é um comando de linha de comando (D29): não sobe o Spring nem exige banco ou .env
        if (args.length > 0 && args[0].equals("decrypt")) {
            System.exit(br.com.vovolinux.m2m.crypto.DecryptCli.run(args, System.out, System.err));
        }
        SpringApplication.run(M2mBackApplication.class, args);
    }
}
