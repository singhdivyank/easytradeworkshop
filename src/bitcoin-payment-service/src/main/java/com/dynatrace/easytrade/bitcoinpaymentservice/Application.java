package com.dynatrace.easytrade.bitcoinpaymentservice;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.filter.ForwardedHeaderFilter;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.servlet.DispatcherType;

@SpringBootApplication
@EnableScheduling
public class Application {
    private static final Logger logger = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        logger.info("Bitcoin Payment Service started.");
        SpringApplication.run(Application.class, args);
    }

    @Bean
    @ConditionalOnMissingBean(ForwardedHeaderFilter.class)
    @ConditionalOnProperty(value = "server.forward-headers-strategy", havingValue = "framework")
    public FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter() {
        ForwardedHeaderFilter filter = new ForwardedHeaderFilter();
        FilterRegistrationBean<ForwardedHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * A bounded HikariCP pool. Unlike credit-card-order-service, which opens a
     * fresh
     * DriverManager connection per call, this service pools connections so that
     * scaling
     * out replicas cannot open an unbounded number of connections against the
     * shared
     * MSSQL instance (the fleet's identified bottleneck). Pool size and connection
     * string are configurable via env so the total connection budget can be
     * governed
     * across replicas at deploy time.
     */
    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("MSSQL_CONNECTIONSTRING"));
        config.setPoolName("bitcoin-payment-pool");
        config.setMaximumPoolSize(readIntEnv("DB_POOL_MAX_SIZE", 10));
        config.setMinimumIdle(readIntEnv("DB_POOL_MIN_IDLE", 2));
        config.setConnectionTimeout(readLongEnv("DB_POOL_CONNECTION_TIMEOUT_MS", 10_000L));
        config.setMaxLifetime(readLongEnv("DB_POOL_MAX_LIFETIME_MS", 600_000L));
        return new HikariDataSource(config);
    }

    @Bean
    public DatabaseHelper databaseHelper(DataSource dataSource) {
        return new DatabaseHelper(dataSource);
    }

    @Bean
    ScheduledExecutorService scheduler() {
        return Executors.newScheduledThreadPool(1);
    }

    private static int readIntEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid value '{}' for {}, using default {}", value, name, fallback);
            return fallback;
        }
    }

    private static long readLongEnv(String name, long fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid value '{}' for {}, using default {}", value, name, fallback);
            return fallback;
        }
    }
}
