package dev.hellowrc.circlechat.controller;

import dev.hellowrc.circlechat.model.dto.UserInfo;
import dev.hellowrc.circlechat.model.dto.requests.AuthLoginReq;
import dev.hellowrc.circlechat.model.dto.responses.AuthLoginRsp;
import dev.hellowrc.circlechat.model.dto.responses.HttpRequestRsp;
import dev.hellowrc.circlechat.repository.IChatroomsRepository;
import dev.hellowrc.circlechat.repository.IUsersRepository;
import dev.hellowrc.circlechat.service.ChatroomService;
import dev.hellowrc.circlechat.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/")
public class AuthenticateController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final UserService userService;
    private final ChatroomService chatroomService;
    private final IChatroomsRepository chatroomsRepository;
    private IUsersRepository usersRepository;

    public AuthenticateController(AuthenticationManager authenticationManager,
                                  SecurityContextRepository securityContextRepository,
                                  UserService userService,
                                  ChatroomService chatroomService,
                                  IChatroomsRepository chatroomsRepository,
                                  IUsersRepository usersRepository) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.userService = userService;
        this.chatroomService = chatroomService;
        this.chatroomsRepository = chatroomsRepository;
        this.usersRepository = usersRepository;
    }

    @PostMapping("login")
    public HttpRequestRsp<AuthLoginRsp> login(@RequestBody AuthLoginReq body,
                                              HttpServletRequest request,
                                              HttpServletResponse response) {
        var token =
                UsernamePasswordAuthenticationToken.unauthenticated(
                        body.username(),
                        body.password()
                );

        // 验证用户名密码
        var authentication = authenticationManager.authenticate(token);

        // 建立 SecurityContext
        var context = SecurityContextHolder.createEmptyContext();

        context.setAuthentication(authentication);

        SecurityContextHolder.setContext(context);

        // 非常重要：保存到 Session
        securityContextRepository.saveContext(
                context,
                request,
                response
        );

        var user = userService.getUserInfoByUsername(body.username());
        chatroomService.addChatroomMemberIfNotExists(
                chatroomsRepository.findById(ChatroomService.MAIN_CHATROOM_ID).orElse(null),
                usersRepository.findByUsername(body.username()));
        return new HttpRequestRsp<>(new AuthLoginRsp(user), 200, "ok");
    }
}
