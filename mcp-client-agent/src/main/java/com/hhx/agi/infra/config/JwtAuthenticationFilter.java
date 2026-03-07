package com.hhx.agi.infra.config;

import com.hhx.agi.infra.dao.UserMapper;
import com.hhx.agi.infra.po.UserPO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(1)
public class JwtAuthenticationFilter implements WebFilter {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserMapper userMapper;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // 登录接口不需要认证
        if (path.startsWith("/api/auth/") || path.startsWith("/static/")
            || path.startsWith("/icon/") || path.equals("/favicon.ico")
            || path.startsWith("/ws/")) {
            return chain.filter(exchange);
        }

        String token = getTokenFromRequest(exchange);

        if (StringUtils.hasText(token) && jwtUtils.validateToken(token)) {
            try {
                String username = jwtUtils.getUsernameFromToken(token);

                LambdaQueryWrapper<UserPO> queryWrapper = new LambdaQueryWrapper<>();
                queryWrapper.eq(UserPO::getUsername, username);
                UserPO user = userMapper.selectOne(queryWrapper);

                if (user != null) {
                    // 设置用户上下文
                    UserContext.setUserContext(user.getUsername(), user.getUsername());
                }
            } catch (Exception e) {
                // Token 无效，继续但不设置用户
            }
        }

        // 请求结束后清理
        return chain.filter(exchange).doFinally(signal -> UserContext.clear());
    }

    private String getTokenFromRequest(ServerWebExchange exchange) {
        String bearerToken = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}