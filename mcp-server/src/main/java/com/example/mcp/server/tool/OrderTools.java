package com.example.mcp.server.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 工具：订单相关业务操作
 * 包含订单查询、退款申请、物流追踪三个 Skill
 */
@Service
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public OrderTools(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== Skill 1: 订单查询 ====================

    @Tool(description = "根据订单号或用户名查询订单详情，包括商品名称、金额、状态、收货地址、快递单号等信息")
    public String queryOrder(
            @ToolParam(description = "订单号，如 ORD20250201001") String orderNo,
            @ToolParam(description = "用户名，如 张三") String username) {

        log.info("[OrderTools] Query order: orderNo={}, username={}", orderNo, username);

        try {
            String sql;
            Object[] params;

            if (orderNo != null && !orderNo.isBlank()) {
                sql = """
                    SELECT o.order_no, u.username, p.name AS product_name, p.category,
                           o.quantity, o.total_amount, o.status, o.shipping_address,
                           o.tracking_no, o.created_at
                    FROM orders o
                    JOIN users u ON o.user_id = u.id
                    JOIN products p ON o.product_id = p.id
                    WHERE o.order_no = ?
                    """;
                params = new Object[]{orderNo.trim()};
            } else if (username != null && !username.isBlank()) {
                sql = """
                    SELECT o.order_no, u.username, p.name AS product_name, p.category,
                           o.quantity, o.total_amount, o.status, o.shipping_address,
                           o.tracking_no, o.created_at
                    FROM orders o
                    JOIN users u ON o.user_id = u.id
                    JOIN products p ON o.product_id = p.id
                    WHERE u.username = ?
                    ORDER BY o.created_at DESC
                    """;
                params = new Object[]{username.trim()};
            } else {
                return "请提供订单号（orderNo）或用户名（username）进行查询";
            }

            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, params);
            if (results.isEmpty()) return "未找到匹配的订单信息";

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(results.size()).append(" 条订单记录：\n\n");

            for (Map<String, Object> row : results) {
                sb.append("订单号: ").append(row.get("ORDER_NO")).append("\n");
                sb.append("用户: ").append(row.get("USERNAME")).append("\n");
                sb.append("商品: ").append(row.get("PRODUCT_NAME")).append("（").append(row.get("CATEGORY")).append("）\n");
                sb.append("数量: ").append(row.get("QUANTITY")).append("\n");
                sb.append("金额: ¥").append(row.get("TOTAL_AMOUNT")).append("\n");
                sb.append("状态: ").append(translateOrderStatus(row.get("STATUS").toString())).append("\n");
                sb.append("地址: ").append(row.get("SHIPPING_ADDRESS")).append("\n");
                Object trackingNo = row.get("TRACKING_NO");
                if (trackingNo != null) sb.append("快递单号: ").append(trackingNo).append("\n");
                sb.append("下单时间: ").append(row.get("CREATED_AT")).append("\n");
                sb.append("---\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[OrderTools] queryOrder error: {}", e.getMessage());
            return "查询订单时出错: " + e.getMessage();
        }
    }

    // ==================== Skill 2: 退款申请 ====================

    @Tool(description = "根据订单号发起退款申请。会校验订单状态、检查是否已有退款记录，然后创建退款单并更新订单状态。")
    public String applyRefund(
            @ToolParam(description = "需要退款的订单号") String orderNo,
            @ToolParam(description = "退款原因") String reason) {

        log.info("[OrderTools] Refund request: orderNo={}, reason={}", orderNo, reason);

        if (orderNo == null || orderNo.isBlank()) return "请提供需要退款的订单号";
        if (reason == null || reason.isBlank()) return "请提供退款原因";

        try {
            // 查询订单
            List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                    "SELECT order_no, total_amount, status FROM orders WHERE order_no = ?",
                    orderNo.trim());

            if (orders.isEmpty()) return "未找到订单号为「" + orderNo + "」的订单";

            Map<String, Object> order = orders.get(0);
            String status = order.get("STATUS").toString();

            if ("CANCELLED".equals(status)) return "订单「" + orderNo + "」已取消，无法申请退款";

            // 检查已有退款
            List<Map<String, Object>> existingRefunds = jdbcTemplate.queryForList(
                    "SELECT refund_no, status FROM refunds WHERE order_no = ?", orderNo.trim());
            if (!existingRefunds.isEmpty()) {
                Map<String, Object> refund = existingRefunds.get(0);
                return "订单「" + orderNo + "」已有退款记录，退款单号: " + refund.get("REFUND_NO")
                        + "，状态: " + translateRefundStatus(refund.get("STATUS").toString());
            }

            // 创建退款
            String refundNo = "REF" + System.currentTimeMillis();
            Object totalAmount = order.get("TOTAL_AMOUNT");

            jdbcTemplate.update(
                    "INSERT INTO refunds (refund_no, order_no, reason, amount, status) VALUES (?, ?, ?, ?, 'PENDING')",
                    refundNo, orderNo.trim(), reason, totalAmount);

            jdbcTemplate.update(
                    "UPDATE orders SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP WHERE order_no = ?",
                    orderNo.trim());

            return "退款申请已提交！\n\n"
                    + "退款单号: " + refundNo + "\n"
                    + "关联订单: " + orderNo + "\n"
                    + "退款金额: ¥" + totalAmount + "\n"
                    + "退款原因: " + reason + "\n"
                    + "当前状态: 待处理\n"
                    + "预计处理时间: 1-3 个工作日\n";
        } catch (Exception e) {
            log.error("[OrderTools] applyRefund error: {}", e.getMessage());
            return "退款申请处理出错: " + e.getMessage();
        }
    }

    // ==================== Skill 3: 物流追踪 ====================

    @Tool(description = "根据订单号追踪物流信息，查看快递公司、快递单号、配送状态和物流时间线")
    public String trackLogistics(
            @ToolParam(description = "需要追踪的订单号") String orderNo) {

        log.info("[OrderTools] Tracking logistics: {}", orderNo);

        if (orderNo == null || orderNo.isBlank()) return "请提供需要追踪的订单号";

        try {
            List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                    "SELECT o.order_no, o.status, o.tracking_no, o.shipping_address, o.created_at, p.name AS product_name "
                            + "FROM orders o JOIN products p ON o.product_id = p.id WHERE o.order_no = ?",
                    orderNo.trim());

            if (orders.isEmpty()) return "未找到订单号为「" + orderNo + "」的订单";

            Map<String, Object> order = orders.get(0);
            String status = order.get("STATUS").toString();
            Object trackingNo = order.get("TRACKING_NO");

            StringBuilder sb = new StringBuilder();
            sb.append("订单物流信息\n\n");
            sb.append("订单号: ").append(order.get("ORDER_NO")).append("\n");
            sb.append("商品: ").append(order.get("PRODUCT_NAME")).append("\n");
            sb.append("收货地址: ").append(order.get("SHIPPING_ADDRESS")).append("\n");

            if (trackingNo == null || trackingNo.toString().isBlank()) {
                if ("PENDING".equals(status)) {
                    sb.append("\n当前状态: 待发货\n商家正在处理您的订单，预计 1-2 天内发货。\n");
                } else if ("CANCELLED".equals(status)) {
                    sb.append("\n当前状态: 订单已取消\n");
                }
                return sb.toString();
            }

            String carrier = getCarrier(trackingNo.toString());
            sb.append("快递公司: ").append(carrier).append("\n");
            sb.append("快递单号: ").append(trackingNo).append("\n\n");
            sb.append("物流轨迹：\n");
            sb.append(generateTimeline(status, order.get("SHIPPING_ADDRESS").toString(),
                    order.get("CREATED_AT").toString()));

            return sb.toString();
        } catch (Exception e) {
            log.error("[OrderTools] trackLogistics error: {}", e.getMessage());
            return "查询物流信息时出错: " + e.getMessage();
        }
    }

    // ==================== 辅助方法 ====================

    private String translateOrderStatus(String status) {
        return switch (status) {
            case "PENDING" -> "待处理";
            case "SHIPPED" -> "已发货";
            case "DELIVERED" -> "已送达";
            case "CANCELLED" -> "已取消";
            default -> status;
        };
    }

    private String translateRefundStatus(String status) {
        return switch (status) {
            case "PENDING" -> "待处理";
            case "APPROVED" -> "已批准";
            case "REJECTED" -> "已拒绝";
            default -> status;
        };
    }

    private String getCarrier(String trackingNo) {
        if (trackingNo.startsWith("SF")) return "顺丰速运";
        if (trackingNo.startsWith("YT")) return "圆通速递";
        if (trackingNo.startsWith("ZT")) return "中通快递";
        if (trackingNo.startsWith("YD")) return "韵达快递";
        return "快递公司";
    }

    private String generateTimeline(String status, String address, String createdAt) {
        StringBuilder timeline = new StringBuilder();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm");

        LocalDateTime orderTime;
        try {
            orderTime = LocalDateTime.parse(createdAt.replace(" ", "T").substring(0, 19));
        } catch (Exception e) {
            orderTime = LocalDateTime.now().minusDays(3);
        }

        timeline.append("  ").append(orderTime.format(fmt)).append(" 商家已发货，包裹正在揽收中\n");
        timeline.append("  ").append(orderTime.plusHours(2).format(fmt)).append(" 快递员已揽件\n");
        timeline.append("  ").append(orderTime.plusHours(6).format(fmt)).append(" 包裹已到达发货城市转运中心\n");
        timeline.append("  ").append(orderTime.plusDays(1).format(fmt)).append(" 包裹运输中\n");

        if ("SHIPPED".equals(status)) {
            timeline.append("  ").append(orderTime.plusDays(1).plusHours(12).format(fmt)).append(" 包裹已到达目的城市，派送中...\n");
            timeline.append("\n当前状态: 派送中，预计今日送达\n");
        } else if ("DELIVERED".equals(status)) {
            timeline.append("  ").append(orderTime.plusDays(2).format(fmt)).append(" 包裹已到达目的城市转运中心\n");
            timeline.append("  ").append(orderTime.plusDays(2).plusHours(4).format(fmt)).append(" 快递员正在派送\n");
            timeline.append("  ").append(orderTime.plusDays(2).plusHours(6).format(fmt)).append(" 已签收\n");
            timeline.append("\n当前状态: 已签收\n签收地址: ").append(address).append("\n");
        }
        return timeline.toString();
    }
}
