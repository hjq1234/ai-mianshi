package com.ke.nhservice.aimianshi.biz.user;

import com.ke.nhservice.aimianshi.common.auth.TokenUtil;
import com.ke.nhservice.aimianshi.common.exception.BizException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private static final String SEED_USERNAME = "admin";
    private static final String SEED_PASSWORD = "admin123";

    private final UserDao userDao;
    private final TokenUtil tokenUtil;
    /** 只用了 BCrypt 一个类，所以不引 Spring Security 全家桶，只引 spring-security-crypto */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserService(UserDao userDao, TokenUtil tokenUtil) {
        this.userDao = userDao;
        this.tokenUtil = tokenUtil;
    }

    public LoginResult login(String username, String rawPassword) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            throw new BizException("用户名和密码不能为空");
        }
        User user = userDao.findByUsername(username.trim())
                .orElseThrow(() -> new BizException("用户名或密码错误"));
        if (!encoder.matches(rawPassword, user.passwordHash())) {
            throw new BizException("用户名或密码错误");
        }
        return new LoginResult(tokenUtil.issue(user.id()), user);
    }

    public void register(String username, String rawPassword, String nickname) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            throw new BizException("用户名和密码不能为空");
        }
        Optional<User> userOptional = userDao.findByUsername(username.trim());
        if(userOptional.isPresent()){
            throw new BizException("当前用户名已被注册");
        }
        userDao.insert(username, encoder.encode(rawPassword), nickname);
    }

    /** 登录成功要同时把 token 和用户信息返回给前端，所以两个一起给 */
    public record LoginResult(String token, User user) {
    }

    public User requireUser(Long userId) {
        return userDao.findById(userId)
                .orElseThrow(() -> BizException.unauthorized("用户不存在"));
    }

    /**
     * 首次启动时建一个演示账号，省掉注册流程（设计文档明确不做注册）。
     * 密码用 BCrypt 存，不存明文。
     */
    @Override
    public void run(ApplicationArguments args) {
        if (userDao.count() > 0) {
            return;
        }
        userDao.insert(SEED_USERNAME, encoder.encode(SEED_PASSWORD), "演示账号");
        log.warn("已创建默认账号 {} / {}，请尽快修改密码（当前没有改密接口，直接改库或删库重建）",
                SEED_USERNAME, SEED_PASSWORD);
    }
}