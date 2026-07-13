package dev.relay.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Configuration
public class DataSourceConfig {

    @Bean
    DataSource dataSource() {
        String databaseUrl = System.getenv("DATABASE_URL");
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setMaximumPoolSize(Integer.parseInt(System.getenv().getOrDefault("DB_POOL_SIZE", "16")));
        dataSource.setMinimumIdle(2);
        dataSource.setConnectionTimeout(10_000);
        dataSource.setPoolName("relay-db");

        if (databaseUrl != null && !databaseUrl.isBlank()) {
            configureFromDatabaseUrl(dataSource, databaseUrl);
        } else {
            dataSource.setJdbcUrl(System.getenv().getOrDefault(
                    "RELAY_DB_URL", "jdbc:postgresql://localhost:5432/relay"));
            dataSource.setUsername(System.getenv().getOrDefault("RELAY_DB_USER", "relay"));
            dataSource.setPassword(System.getenv().getOrDefault("RELAY_DB_PASSWORD", "relay"));
        }

        return dataSource;
    }

    private void configureFromDatabaseUrl(HikariDataSource dataSource, String databaseUrl) {
        URI uri = URI.create(databaseUrl);
        String rawUserInfo = uri.getRawUserInfo();
        if (rawUserInfo == null || rawUserInfo.isBlank()) {
            throw new IllegalArgumentException("DATABASE_URL must include database credentials");
        }

        String[] credentials = rawUserInfo.split(":", 2);
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath();
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null && !rawQuery.isBlank()) {
            // libpq connection strings use channel_binding; pgJDBC names the property channelBinding.
            jdbcUrl += "?" + rawQuery.replace("channel_binding=", "channelBinding=");
        }

        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername(decode(credentials[0]));
        dataSource.setPassword(credentials.length > 1 ? decode(credentials[1]) : "");
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
