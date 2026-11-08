package com.markagent.auth;

import com.markagent.common.dto.AuthResponse;
import com.markagent.common.dto.UserRecord;
import com.markagent.config.MarkAgentProperties;
import com.markagent.store.UserStore;
import com.markagent.util.Ids;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.markagent.api.GlobalExceptionHandler.ApiException;

import java.time.Instant;

/** 注册/登录。bootstrap-admin：首次启动（无任何用户时）自动建管理员 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserStore userStore;
    private final JwtTokenProvider tokenProvider;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final MarkAgentProperties.Security.BootstrapAdmin bootstrapAdmin;

    public AuthService(UserStore userStore, JwtTokenProvider tokenProvider, MarkAgentProperties props) {
        this.userStore = userStore;
        this.tokenProvider = tokenProvider;
        this.bootstrapAdmin = props.security().bootstrapAdmin();
    }

    @PostConstruct
    void seedBootstrapAdmin() {
        if (userStore.listAll().isEmpty()) {
            userStore.save(new UserStore.UserEntry(Ids.user(), bootstrapAdmin.username(), "管理员",
                    encoder.encode(bootstrapAdmin.password()), Instant.now().toString()));
            log.info("已创建 bootstrap 管理员 {}", bootstrapAdmin.username());
        }
    }

    public AuthResponse register(String username, String password, String displayName) {
        if (username == null || username.isBlank() || password == null || password.length() < 6) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "用户名不能为空，密码至少 6 位");
        }
        if (userStore.findByUsername(username).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "用户名已被注册: " + username);
        }
        UserStore.UserEntry user = new UserStore.UserEntry(Ids.user(), username,
                displayName == null || displayName.isBlank() ? username : displayName,
                encoder.encode(password), Instant.now().toString());
        userStore.save(user);
        return new AuthResponse(tokenProvider.issue(user.id()), user.toRecord());
    }

    public AuthResponse login(String username, String password) {
        UserStore.UserEntry user = userStore.findByUsername(username).orElseThrow(
                () -> new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或密码错误"));
        if (!encoder.matches(password == null ? "" : password, user.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或密码错误");
        }
        return new AuthResponse(tokenProvider.issue(user.id()), user.toRecord());
    }

    public UserRecord me(String userId) {
        return userStore.findById(userId).map(UserStore.UserEntry::toRecord).orElseThrow(
                () -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "用户不存在"));
    }
}
