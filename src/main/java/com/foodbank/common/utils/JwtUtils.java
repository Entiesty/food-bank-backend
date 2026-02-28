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
     * 1. 登录成功后：签发 Token，并存入 Redis (🚨 核心修改：增加 role 参数)
     */
    public String generateTokenAndCache(Long userId, Byte role) {
        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role) // 🚨 核心王牌：将角色信息刻入 JWT Payload 中
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME_MS))
                .signWith(SECRET_KEY)
                .compact();

        String redisKey = REDIS_TOKEN_PREFIX + userId;
        redisTemplate.opsForValue().set(redisKey, token, EXPIRATION_TIME_MS, TimeUnit.MILLISECONDS);

        log.info("用户 [{}] (角色:{}) 登录成功，Token 已生成", userId, role);
        return token;
    }

    /**
     * 2. 拦截器校验：解析 Token
     * @return 返回包含 userId 和 role 的对象
     */
    public TokenInfo validateTokenAndCheckRedis(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(SECRET_KEY)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Long userId = Long.parseLong(claims.getSubject());
            // 🚨 解析角色信息 (注意 JSON 序列化时数字可能变成 Integer)
            Integer roleInt = claims.get("role", Integer.class);
            Byte role = roleInt != null ? roleInt.byteValue() : null;

            String redisKey = REDIS_TOKEN_PREFIX + userId;
            String redisToken = redisTemplate.opsForValue().get(redisKey);

            if (!StringUtils.hasText(redisToken) || !redisToken.equals(token)) {
                return null; // Redis 校验不通过
            }

            return new TokenInfo(userId, role); // 校验全部通过，返回完整信息

        } catch (Exception e) {
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

    /**
     * 内部数据类，用于封装解析后的 Token 结果
     */
    public static class TokenInfo {
        public Long userId;
        public Byte role;
        public TokenInfo(Long userId, Byte role) {
            this.userId = userId;
            this.role = role;
        }
    }
}