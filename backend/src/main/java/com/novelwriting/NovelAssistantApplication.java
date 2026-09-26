package com.novelwriting;

import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
public class NovelAssistantApplication {

    public static void main(String[] args) {
        var app = new SpringApplication(NovelAssistantApplication.class);
        boolean maintenance = java.util.Arrays.stream(args).anyMatch(arg -> arg.startsWith("--maintenance.operation="));
        if (maintenance) app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        var context = app.run(args);
        if (maintenance) {
            int status = 0;
            try {
                var env = context.getEnvironment();
                String operation = env.getRequiredProperty("maintenance.operation");
                var images = context.getBean(com.novelwriting.maintenance.ImageMaintenance.class);
                switch (operation) {
                    case "scan-images" -> images.scan().forEach(System.out::println);
                    case "quarantine-images" -> System.out.println(images.quarantine());
                    case "restore-images" -> System.out.println("Restored files: " + images.restore(env.getRequiredProperty("maintenance.batch")));
                    case "rotate-key" -> {
                        var path = java.nio.file.Path.of(env.getRequiredProperty("maintenance.new-key-file"));
                        int count = context.getBean(com.novelwriting.maintenance.CredentialRotation.class).rotate(path);
                        System.out.println("Rotated " + count + " credentials. Set NOVEL_CREDENTIAL_KEY_FILE to " + path.toAbsolutePath() + " before restarting. Keep the old key with old database backups.");
                    }
                    default -> throw new IllegalArgumentException("Unknown maintenance operation");
                }
            } catch (Exception e) { System.err.println("Maintenance failed: " + e.getClass().getSimpleName()); status = 1; }
            finally { context.close(); }
            System.exit(status);
        }
    }

    @Bean
    public Hibernate6Module hibernate6Module() {
        Hibernate6Module module = new Hibernate6Module();
        module.disable(Hibernate6Module.Feature.USE_TRANSIENT_ANNOTATION);
        return module;
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins("*")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}
