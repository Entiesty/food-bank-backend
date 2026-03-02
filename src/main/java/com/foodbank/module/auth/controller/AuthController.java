package com.foodbank.module.auth.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.foodbank.common.api.Result;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.JwtUtils;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.service.IUserService;
import com.foodbank.module.auth.model.vo.LoginVO;
import com.foodbank.module.auth.model.dto.RegisterDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@Slf4j
@Tag(name = "安全认证接口", description = "负责用户登录、注册、找回密码、验证码及Token签发")
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String SMS_CODE_PREFIX = "sms:code:";

    @Operation(summary = "0. 获取短信验证码", description = "生成验证码存入Redis并返回给前端模拟手机弹窗")
    @GetMapping("/send-code")
    public Result<String> sendSmsCode(@RequestParam String phone,
                                      @Parameter(description = "场景：register-注册, forgot-找回密码")
                                      @RequestParam(defaultValue = "register") String type) {
        if (!StringUtils.hasText(phone) || phone.length() != 11) {
            throw new BusinessException("请输入正确的 11 位手机号码");
        }

        long count = userService.count(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if ("register".equals(type) && count > 0) {
            throw new BusinessException("该手机号已被注册，请直接去登录");
        } else if ("forgot".equals(type) && count == 0) {
            throw new BusinessException("该手机号尚未注册，请先注册");
        }

        String redisKey = SMS_CODE_PREFIX + phone;
        if (stringRedisTemplate.hasKey(redisKey)) {
            throw new BusinessException("验证码已发送，请不要频繁获取！(1分钟后再试)");
        }
        String code = String.format("%06d", new Random().nextInt(999999));
        stringRedisTemplate.opsForValue().set(redisKey, code, 5, TimeUnit.MINUTES);
        log.info("📞 [模拟短信] 向手机号 {} 发送验证码: {}", phone, code);
        return Result.success(code, "验证码发送成功");
    }

    @Operation(summary = "1. 多角色开放注册入口", description = "支持多角色注册，包含 Redis 验证码比对与商家审核状态机")
    @PostMapping("/register")
    public Result<String> register(@RequestBody RegisterDTO dto) {
        if (!StringUtils.hasText(dto.getPhone()) || !StringUtils.hasText(dto.getPassword())
                || !StringUtils.hasText(dto.getUsername()) || !StringUtils.hasText(dto.getSmsCode())) {
            throw new BusinessException("请将注册信息填写完整");
        }

        String redisKey = SMS_CODE_PREFIX + dto.getPhone();
        String savedCode = stringRedisTemplate.opsForValue().get(redisKey);
        if (!StringUtils.hasText(savedCode)) throw new BusinessException("验证码已过期或未发送，请重新获取");
        if (!savedCode.equals(dto.getSmsCode())) throw new BusinessException("验证码错误，请检查后重试");

        stringRedisTemplate.delete(redisKey); // 阅后即焚

        Byte reqRole = dto.getRole();
        if (reqRole == null || (reqRole != 1 && reqRole != 2 && reqRole != 3)) {
            throw new BusinessException("非法的角色选择！");
        }

        // 🚨 核心逻辑新增：校验商家的资质凭证
        if (reqRole == 2 && !StringUtils.hasText(dto.getIdentityProofUrl())) {
            throw new BusinessException("爱心商家入驻必须上传营业执照图片");
        }

        long count = userService.count(new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getPhone()));
        if (count > 0) throw new BusinessException("该手机号已被注册，请直接登录");

        User user = new User();
        user.setPhone(dto.getPhone());
        user.setUsername(dto.getUsername());
        user.setPassword(DigestUtils.md5DigestAsHex(dto.getPassword().getBytes()));
        user.setRole(reqRole);
        user.setCreditScore(0);
        user.setUserTag(reqRole == 1 ? "ELDERLY" : "NORMAL");

        // 🚨 核心逻辑新增：落库凭证URL，并初始化核验状态为 0 (未核实)
        user.setIdentityProofUrl(dto.getIdentityProofUrl());
        user.setIsVerified((byte) 0);

        user.setCreateTime(java.time.LocalDateTime.now());
        user.setStatus((byte) (reqRole == 2 ? 0 : 1)); // 商家为 0 待审核

        userService.save(user);

        return reqRole == 2
                ? Result.success(null, "注册成功！您的商家资质正在人工审核中，请留意后续通知。")
                : Result.success(null, "注册成功！欢迎加入社区食物银行。");
    }

    @Operation(summary = "2. 系统统一登录入口", description = "校验手机号与密码，包含状态机拦截")
    @PostMapping("/login")
    public Result<LoginVO> login(@RequestParam String phone, @RequestParam String password) {
        User user = userService.getOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (user == null) throw new BusinessException("该手机号未注册");

        String md5Password = DigestUtils.md5DigestAsHex(password.getBytes());
        if (!user.getPassword().equals(md5Password)) throw new BusinessException("密码错误，请重新输入");

        if (user.getStatus() == 0) {
            if (user.getRole() == 2) throw new BusinessException("账号审核中：您的爱心商家资质尚未通过审批，请稍后");
            throw new BusinessException("该账号已被系统封禁或尚未激活");
        }

        // 🚨 漏洞修复：移除了对 role == 2 的非法拦截！允许 1,2,3,4 均可登录
        if (user.getRole() < 1 || user.getRole() > 4) {
            throw new BusinessException("权限不足：系统暂未对该角色开放登录");
        }

        Long realUserId = user.getUserId();
        String token = jwtUtils.generateTokenAndCache(realUserId, user.getRole());

        LoginVO loginVO = LoginVO.builder()
                .token(token).userId(realUserId)
                .username(user.getUsername()).role(user.getRole()).build();

        return Result.success(loginVO, "登录成功，欢迎回来：" + user.getUsername());
    }

    @Operation(summary = "3. 忘记密码 - 重置", description = "通过短信验证码校验，重置用户密码")
    @PostMapping("/reset-password")
    public Result<String> resetPassword(@RequestParam String phone, @RequestParam String smsCode, @RequestParam String newPassword) {
        if (!StringUtils.hasText(phone) || !StringUtils.hasText(smsCode) || !StringUtils.hasText(newPassword)) {
            throw new BusinessException("请将信息填写完整");
        }

        String redisKey = SMS_CODE_PREFIX + phone;
        String savedCode = stringRedisTemplate.opsForValue().get(redisKey);
        if (!StringUtils.hasText(savedCode)) throw new BusinessException("验证码已过期或未获取");
        if (!savedCode.equals(smsCode)) throw new BusinessException("验证码错误，请检查");

        User user = userService.getOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (user == null) throw new BusinessException("该手机号尚未注册");

        user.setPassword(DigestUtils.md5DigestAsHex(newPassword.getBytes()));
        userService.updateById(user);
        stringRedisTemplate.delete(redisKey);

        return Result.success("密码重置成功，请使用新密码登录！");
    }

    @Operation(summary = "4. 强制登出 / 下线", description = "直接删除 Redis 中的 Token 缓存")
    @PostMapping("/logout")
    public Result<String> logout(@RequestParam Long userId) {
        jwtUtils.invalidateToken(userId);
        return Result.success("账号已成功退出登录");
    }
}