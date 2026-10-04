package dev.hellowrc.circlechat.controller;

import dev.hellowrc.circlechat.model.dto.requests.RegisterUserReq;
import dev.hellowrc.circlechat.model.dto.responses.HttpRequestRsp;
import dev.hellowrc.circlechat.model.dto.responses.RegisterUserRsp;
import dev.hellowrc.circlechat.model.dto.responses.UserInfoRsp;
import dev.hellowrc.circlechat.repository.IUsersRepository;
import dev.hellowrc.circlechat.service.UserService;
import org.aspectj.apache.bcel.generic.RET;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
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
    public HttpRequestRsp<RegisterUserRsp> Register(@RequestBody RegisterUserReq request){
        userService.createUser(request.username(), request.email(), request.displayName(), request.password());

        return new HttpRequestRsp<>(null, 200, "ok");
    }

    @GetMapping("me")
    public HttpRequestRsp<UserInfoRsp> Me(Principal principal) {
        var user = userService.getUserInfoByUsername(principal.getName());

        return new HttpRequestRsp<>(new UserInfoRsp(user), 200, "ok");
    }
}
