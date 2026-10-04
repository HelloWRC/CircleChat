package dev.hellowrc.circlechat.repository;

import dev.hellowrc.circlechat.model.entitiy.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IUsersRepository extends JpaRepository<User, Long> {

    User findByUsername(String username);
}
