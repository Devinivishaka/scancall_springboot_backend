package protonest.co.scancallnewbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ScancallNewBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScancallNewBackendApplication.class, args);
    }

}
