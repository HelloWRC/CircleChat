package dev.hellowrc.circlechat.repository;

import dev.hellowrc.circlechat.model.entitiy.Friendship;
import dev.hellowrc.circlechat.model.entitiy.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IFriendshipsRepository extends JpaRepository<Friendship, Long> {
    @EntityGraph(attributePaths = {"conversation", "userA", "userB"})
    @Query("""
            select f from Friendship f where f.conversation.id in :conversationIds
            and (f.userA.username = :username or f.userB.username = :username)
            """)
    List<Friendship> findForUserAndConversations(String username, Collection<Long> conversationIds);

    @Query("""
            select u from User u where exists (
                select f.id from Friendship f
                where (f.userA.username = :username and f.userB = u)
                   or (f.userB.username = :username and f.userA = u)
            )
            order by u.displayName, u.username
            """)
    List<User> findFriends(@Param("username") String username);

    @Query("""
            select case when count(x) > 0 then true else false end
            from Friendship x
            where (x.userA.username = :usernameA and x.userB.username = :usernameB)
               or (x.userB.username = :usernameA and x.userA.username = :usernameB)
            """)
    boolean existsFriendshipOf(@Param("usernameA") String usernameA, @Param("usernameB") String usernameB);

    @Query("""
            select f from Friendship f
            where (f.userA.username = :usernameA and f.userB.username = :usernameB)
               or (f.userB.username = :usernameA and f.userA.username = :usernameB)
            """)
    Optional<Friendship> findFriendshipOf(String usernameA, String usernameB);
}
