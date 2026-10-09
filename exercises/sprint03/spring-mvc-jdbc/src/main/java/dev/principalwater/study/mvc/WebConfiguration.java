package dev.principalwater.study.mvc;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@Configuration(proxyBeanMethods = false)
@EnableWebMvc
@ComponentScan(basePackageClasses = WebConfiguration.class)
@PropertySource("classpath:application.properties")
public class WebConfiguration {
    @Bean
    static PropertySourcesPlaceholderConfigurer placeholders() {
        return new PropertySourcesPlaceholderConfigurer();
    }

    @Bean
    DataSource dataSource(@Value("${database.url}") String url,
            @Value("${database.username}") String username,
            @Value("${database.password}") String password) {
        var source = new DriverManagerDataSource(url, username, password);
        source.setDriverClassName("org.h2.Driver");
        // Схема готова до создания DAO; событие child/parent не повторяет INSERT.
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        return source;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource source) {
        return new JdbcTemplate(source);
    }

    @Bean
    StandardServletMultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver();
    }
}
