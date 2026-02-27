package com.hhx.agi.infra.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.hhx.agi.infra.dao")
public class MyBatisConfig {
}
