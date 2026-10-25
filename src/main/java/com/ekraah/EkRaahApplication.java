package com.ekraah;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class EkRaahApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        bootstrapProfile(dotenv, args);
        springProperty(dotenv, "APP_OTP_SENDER", "app.otp.sender");
        SpringApplication.run(EkRaahApplication.class, args);
    }

    /**
     * SPRING_PROFILES_ACTIVE inside .env cannot drive profile activation on its
     * own — Spring resolves profiles before custom property sources (dotenv) are
     * attached. So read it here, before the context starts, honoring Spring's
     * precedence: command-line flag > real env var > .env.
     */
    private static void bootstrapProfile(Dotenv dotenv, String[] args) {
        SimpleCommandLinePropertySource cmd = new SimpleCommandLinePropertySource(args);
        if (cmd.containsProperty("spring.profiles.active")) return;

        String profile = System.getenv("SPRING_PROFILES_ACTIVE");
        if (profile == null || profile.isBlank()) {
            profile = dotenv.get("SPRING_PROFILES_ACTIVE");
        }
        if (profile != null && !profile.isBlank()) {
            System.setProperty("spring.profiles.active", profile.trim());
        }
    }

    /**
     * Most .env keys work as-is: yml references them via ${...} placeholders, and
     * the spring-dotenv property source resolves exact names. Only keys bound
     * directly to @ConfigurationProperties without a placeholder need help —
     * system properties must carry the dotted Spring name, because the env-var
     * (UPPERCASE_UNDERSCORE) relaxed mapping applies to real env vars only.
     */
    private static void springProperty(Dotenv dotenv, String envKey, String propertyName) {
        if (System.getProperty(propertyName) != null) return;   // -D wins
        if (System.getenv(envKey) != null) return;              // real env var wins
        String value = dotenv.get(envKey);                      // .env fills the gap
        if (value != null && !value.isBlank()) {
            System.setProperty(propertyName, value.trim());
        }
    }
}
