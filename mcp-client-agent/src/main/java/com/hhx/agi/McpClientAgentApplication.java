package com.hhx.agi;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.CommandLineRunner;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * MCP Client Agent 启动类
 * 通过 MCP 协议连接 MCP Server，结合 LLM 做智能决策
 */
@Slf4j
@SpringBootApplication(scanBasePackages = "com.hhx.agi")
public class McpClientAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpClientAgentApplication.class, args);
    }

    @Bean
    public CommandLineRunner printDataSource(javax.sql.DataSource dataSource) {
        return args -> {
            if (dataSource instanceof HikariDataSource hikari) {
                log.info("============== 数据库连接信息 ==============");
                log.info("JDBC URL: {}", hikari.getJdbcUrl());
                log.info("用户名: {}", hikari.getUsername());
                log.info("驱动: {}", hikari.getDriverClassName());

                try (Connection conn = dataSource.getConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT DATABASE()")) {
                    if (rs.next()) {
                        log.info("当前数据库: {}", rs.getString(1));
                    }
                }
                log.info("==========================================");
            }
        };
    }
}
