package uk.jtoye.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

@SpringBootApplication
@RestController
public class CoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }

    @GetMapping("/")
    public RedirectView redirectToSwagger() {
        return new RedirectView("/swagger-ui.html");
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
