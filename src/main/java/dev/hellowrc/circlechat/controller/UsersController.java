package dev.hellowrc.circlechat.controller;

import dev.hellowrc.circlechat.exception.ApiException;
import dev.hellowrc.circlechat.model.dto.requests.ChangePasswordReq;
import dev.hellowrc.circlechat.model.dto.requests.RegisterUserReq;
import dev.hellowrc.circlechat.model.dto.requests.UpdateProfileReq;
import dev.hellowrc.circlechat.model.dto.responses.HttpRequestRsp;
import dev.hellowrc.circlechat.model.dto.responses.RegisterUserRsp;
import dev.hellowrc.circlechat.model.dto.responses.UserInfoRsp;
import dev.hellowrc.circlechat.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/users/")
public class UsersController {

    private final UserService userService;

    UsersController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("register")
    public HttpRequestRsp<RegisterUserRsp> Register(@RequestBody RegisterUserReq request) {
        userService.createUser(request.username(), request.email(), request.displayName(), request.password());

        return new HttpRequestRsp<>(null, 200, "ok");
    }

    @GetMapping("me")
    public HttpRequestRsp<UserInfoRsp> Me(Principal principal) {
        var user = userService.getUserInfoByUsername(principal.getName());

        return new HttpRequestRsp<>(new UserInfoRsp(user), 200, "ok");
    }

    @PatchMapping("me")
    @Operation(summary = "修改当前用户的显示名称")
    public HttpRequestRsp<UserInfoRsp> updateProfile(Principal principal, @RequestBody UpdateProfileReq request) {
        var user = userService.updateDisplayName(principal.getName(), request.displayName());
        return new HttpRequestRsp<>(new UserInfoRsp(user), 200, "ok");
    }

    @PutMapping("me/password")
    @Operation(summary = "修改当前用户的密码", description = "验证当前密码后更新，保持已有登录会话有效。")
    public HttpRequestRsp<Void> changePassword(Principal principal, @RequestBody ChangePasswordReq request) {
        userService.changePassword(principal.getName(), request.currentPassword(), request.newPassword());
        return new HttpRequestRsp<>(null, 200, "ok");
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<HttpRequestRsp<Void>> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(new HttpRequestRsp<>(null, exception.getStatusCode(), exception.getMessage()));
    }
}
