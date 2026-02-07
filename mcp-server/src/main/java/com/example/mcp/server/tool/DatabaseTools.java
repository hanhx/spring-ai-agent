package com.example.mcp.server.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * MCP Server 工具：数据库查询
 * 使用 JdbcTemplate 执行真实 SQL 查询，仅允许 SELECT
 */
@Service
public class DatabaseTools {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTools.class);

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public DatabaseTools(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = "查询业务数据库，仅允许 SELECT 语句。可用表：users(id,username,email,phone), products(id,name,category,price,stock,description), orders(id,order_no,user_id,product_id,quantity,total_amount,status,shipping_address,tracking_no,created_at), refunds(id,refund_no,order_no,reason,amount,status,created_at)。订单状态: PENDING/SHIPPED/DELIVERED/CANCELLED，退款状态: PENDING/APPROVED/REJECTED。")
    public String queryDatabase(
            @ToolParam(description = "SQL SELECT 查询语句") String sql) {

        log.info("[DatabaseTools] Executing SQL: {}", sql);

        if (sql == null || sql.isBlank()) {
            return "错误：SQL 语句不能为空";
        }

        String trimmedSql = sql.trim().toUpperCase();
        if (!trimmedSql.startsWith("SELECT")) {
            return "错误：仅允许 SELECT 查询语句";
        }
        if (trimmedSql.contains("DROP") || trimmedSql.contains("DELETE") || trimmedSql.contains("UPDATE")
                || trimmedSql.contains("INSERT") || trimmedSql.contains("ALTER") || trimmedSql.contains("TRUNCATE")) {
            return "错误：检测到危险 SQL 关键字，查询被拒绝";
        }

        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);

            if (results.isEmpty()) {
                return "查询成功，但没有找到匹配的数据。SQL: " + sql;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("查询结果（共 ").append(results.size()).append(" 条记录）：\n\n");

            List<String> columns = results.get(0).keySet().stream().toList();
            sb.append("| ");
            for (String col : columns) sb.append(col).append(" | ");
            sb.append("\n|");
            for (String col : columns) sb.append("---|");
            sb.append("\n");

            for (Map<String, Object> row : results) {
                sb.append("| ");
                for (String col : columns) {
                    Object val = row.get(col);
                    sb.append(val != null ? val.toString() : "NULL").append(" | ");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[DatabaseTools] SQL error: {}", e.getMessage());
            return "SQL 执行错误: " + e.getMessage();
        }
    }
}
