package com.foodbank.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * 增强版 JWT 工具类 (JWT + Redis 双重校验)
 */
@Slf4j
@Component
public class JwtUtils {

    // 签名密钥 (实际项目中建议放入 application.yml)
    private static final String SECRET_STRING = "CommunityFoodBankLbsDispatchSystemSuperSecretKey2026";
    private static final SecretKey SECRET_KEY = Keys.hmacShaKeyFor(SECRET_STRING.getBytes());

    // 过期时间：7 天
    private static final long EXPIRATION_TIME_MS = 7 * 24 * 60 * 60 * 1000L;
    // Redis Key 的统一前缀
    private static final String REDIS_TOKEN_PREFIX = "security:token:user:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 1. 登录成功后：签发 Token，并存入 Redis
     */
    public String generateTokenAndCache(Long userId) {
        // 1.1 生成原生的 JWT
        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME_MS))
                .signWith(SECRET_KEY)
                .compact();

        // 1.2 🚨 核心王牌：将 Token 存入 Redis (设置相同的过期时间)
        // Key 格式 -> security:token:user:888  | Value -> 刚生成的 jwt
        String redisKey = REDIS_TOKEN_PREFIX + userId;
        redisTemplate.opsForValue().set(redisKey, token, EXPIRATION_TIME_MS, TimeUnit.MILLISECONDS);

        log.info("用户 [{}] 登录成功，Token 已生成并存入 Redis", userId);
        return token;
    }

    /**
     * 2. 拦截器校验：解析 Token，并与 Redis 中的数据进行“双重比对”
     * @return 校验通过返回 userId；失败或被踢下线返回 null
     */
    public Long validateTokenAndCheckRedis(String token) {
        try {
            // 2.1 第一重校验：验证 JWT 自身的合法性和是否过期
            Claims claims = Jwts.parser()
                    .verifyWith(SECRET_KEY)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId = Long.parseLong(claims.getSubject());

            // 2.2 🚨 第二重校验：去 Redis 里查当前的 Token 是否匹配
            String redisKey = REDIS_TOKEN_PREFIX + userId;
            String redisToken = redisTemplate.opsForValue().get(redisKey);

            if (!StringUtils.hasText(redisToken)) {
                log.warn("用户 [{}] 的 Token 在 Redis 中不存在 (可能已主动注销或被管理员踢出)", userId);
                return null;
            }
            if (!redisToken.equals(token)) {
                log.warn("用户 [{}] 的 Token 与 Redis 中不匹配 (该账号已在其他设备登录，当前设备被顶号)", userId);
                return null;
            }

            // 两重校验全部通过！
            return userId;

        } catch (Exception e) {
            log.error("JWT 本身解析失败 (被篡改或已过期): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 3. 主动登出 / 强制下线：只需删除 Redis 中的 Key 即可
     */
    public void invalidateToken(Long userId) {
        redisTemplate.delete(REDIS_TOKEN_PREFIX + userId);
        log.info("用户 [{}] 的 Token 已被主动销毁，瞬间失效", userId);
    }
}