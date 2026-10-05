package dev.hellowrc.circlechat.service;

import dev.hellowrc.circlechat.model.dto.UserInfo;
import dev.hellowrc.circlechat.model.entitiy.User;
import dev.hellowrc.circlechat.model.entitiy.UserRole;
import dev.hellowrc.circlechat.repository.IUsersRepository;
import dev.hellowrc.circlechat.utils.GravatarUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService implements UserDetailsService {
    public static final String SUPER_ADMIN_USERNAME = "root";

    public static String getRoleNameByRole(UserRole role) {
        if (role == UserRole.User) return "ROLE_USER";
        if (role == UserRole.Admin) return "ROLE_ADMIN";

        return "ROLE_USER";
    }

    private final PasswordEncoder passwordEncoder;
    private final IUsersRepository usersRepository;

    public UserService(PasswordEncoder passwordEncoder, IUsersRepository usersRepository) {
        this.passwordEncoder = passwordEncoder;
        this.usersRepository = usersRepository;
    }

    private void createUserCore(String username, String email, String displayName, String password, UserRole role) {
        var user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setDisplayName(displayName);
        var passwordHash = passwordEncoder.encode(password);
        user.setPasswordHash(passwordHash);
        user.setRole(role);
        usersRepository.save(user);
    }

    public void createUser(String username, String email, String displayName, String password) {
        createUserCore(username, email, displayName, password, UserRole.User);
    }

    public void createDefaultSuperAdmin() {
        createUserCore(SUPER_ADMIN_USERNAME, "", "超级管理员", "believe_the_rainbow", UserRole.Admin);
    }

    public UserInfo getUserInfoByUsername(String username) {
        var user = usersRepository.findByUsername(username);
        return new UserInfo(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getEmail(),
                GravatarUtils.getAvatarUrl(user.getEmail()),
                GravatarUtils.getAvatarUrl(user.getEmail(), GravatarUtils.LargeAvatarSize),
                GravatarUtils.getAvatarUrl(user.getEmail(), GravatarUtils.SmallAvatarSize),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }

    @Override
    public UserDetails loadUserByUsername(@NonNull String username) throws UsernameNotFoundException {
        var userEntity = usersRepository.findByUsername(username);
        if (userEntity == null) {
            throw new UsernameNotFoundException(username + " not found");
        }

        return org.springframework.security.core.userdetails.User
                .withUsername(userEntity.getUsername())
                .password(userEntity.getPasswordHash())
                .authorities(getRoleNameByRole(userEntity.getRole()))
                .build();
    }
}
