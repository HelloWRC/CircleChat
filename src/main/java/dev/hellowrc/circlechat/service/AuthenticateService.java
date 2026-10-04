package dev.hellowrc.circlechat.service;

import dev.hellowrc.circlechat.repository.IUsersRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthenticateService {
    private final PasswordEncoder passwordEncoder;
    private final IUsersRepository usersRepository;

    public AuthenticateService(PasswordEncoder passwordEncoder, IUsersRepository usersRepository) {
        this.passwordEncoder = passwordEncoder;
        this.usersRepository = usersRepository;
    }


}
