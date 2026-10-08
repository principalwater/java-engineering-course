package dev.principalwater.blog.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@ComponentScan({"dev.principalwater.blog.dao", "dev.principalwater.blog.service"})
public class DataConfig {
    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(Environment environment) {
        var config = new HikariConfig();
        config.setJdbcUrl(environment.getProperty("DB_URL",
                "jdbc:h2:file:./data/blog;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
        // Tomcat инициализирует DriverManager до загрузки JDBC-драйверов из WEB-INF/lib.
        config.setDriverClassName(config.getJdbcUrl().startsWith("jdbc:postgresql:")
                ? "org.postgresql.Driver" : "org.h2.Driver");
        config.setUsername(environment.getProperty("DB_USER", "sa"));
        config.setPassword(environment.getProperty("DB_PASSWORD", ""));
        config.setMaximumPoolSize(5);
        config.setPoolName("blog-database");
        return new HikariDataSource(config);
    }

    @Bean
    public DataSourceInitializer schemaInitializer(DataSource dataSource) {
        var initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(new ResourceDatabasePopulator(
                new ClassPathResource("schema.sql")));
        return initializer;
    }

    @Bean
    public NamedParameterJdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
