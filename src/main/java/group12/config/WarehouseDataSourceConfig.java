package group12.config;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class WarehouseDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties tradingDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
        public DataSource tradingDataSource(
            @org.springframework.beans.factory.annotation.Qualifier("tradingDataSourceProperties")
            DataSourceProperties tradingDataSourceProperties
        ) {
        return tradingDataSourceProperties.initializeDataSourceBuilder().build();
    }

    @Bean(name = "warehouseDataSourceProperties")
    @ConfigurationProperties("warehouse.datasource")
    public DataSourceProperties warehouseDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "warehouseDataSource")
    public DataSource warehouseDataSource(
            @org.springframework.beans.factory.annotation.Qualifier("warehouseDataSourceProperties")
            DataSourceProperties properties
    ) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean(name = "warehouseJdbcTemplate")
    public JdbcTemplate warehouseJdbcTemplate(DataSource warehouseDataSource) {
        return new JdbcTemplate(warehouseDataSource);
    }
}
