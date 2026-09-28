package com.ke.nhservice.aimianshi.controller;

import com.ke.nhservice.aimianshi.biz.user.User;
import com.ke.nhservice.aimianshi.biz.user.UserService;
import com.ke.nhservice.aimianshi.common.auth.UserContext;
import com.ke.nhservice.aimianshi.common.dto.ApiResponse;
import com.ke.nhservice.aimianshi.common.dto.LoginRequest;
import com.ke.nhservice.aimianshi.common.dto.LoginVO;
import com.ke.nhservice.aimianshi.common.dto.MeVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginVO> login(@RequestBody LoginRequest request) {
        UserService.LoginResult result = userService.login(request.username(), request.password());
        return ApiResponse.ok(new LoginVO(result.token(), result.user().nickname()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        // token 无法主动失效（见 TokenUtil 注释），登出只是前端删掉本地 token
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<MeVO> me() {
        User user = userService.requireUser(UserContext.get());
        return ApiResponse.ok(new MeVO(user.id(), user.username(), user.nickname()));
    }
}