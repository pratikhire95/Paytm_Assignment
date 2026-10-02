package com.example.paytm.seatManagement.config;

import com.example.paytm.seatManagement.auth.TokenService;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Wires the hand-built pieces (settings, tokens, connection pool) into the Spring context. */
@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public AppSettings appSettings(Environment env) {
        AppSettings settings = new AppSettings(env::getProperty);
        log.info("configuration loaded: database={} pool_size={} token_mint_enabled={} public_logs_enabled={}",
                settings.describeDatabase(), settings.dbPoolSize(), settings.tokenMintEnabled(),
                settings.publicLogsEnabled());
        return settings;
    }

    @Bean
    public TokenService tokenService(AppSettings settings) {
        return new TokenService(settings.tokenSecret(), settings.adminToken(), settings.tokenTtlSeconds(),
                Clock.systemUTC());
    }

    /**
     * The single primary DataSource. Defining it here (instead of spring.datasource.* properties) keeps pool
     * behaviour explicit and lets DATABASE_URL be given in the postgres:// form that hosting platforms use.
     * Flyway and everything else pick this bean up automatically.
     */
    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(AppSettings settings) {
        return Pools.mainPool(settings);
    }
}
