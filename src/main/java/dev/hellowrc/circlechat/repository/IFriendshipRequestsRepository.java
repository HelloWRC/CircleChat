package dev.hellowrc.circlechat.repository;

import dev.hellowrc.circlechat.model.entitiy.FriendshipRequest;
import dev.hellowrc.circlechat.model.entitiy.FriendshipRequestState;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface IFriendshipRequestsRepository extends JpaRepository<FriendshipRequest, Long> {
    @Query("""
            select case when count(x) > 0 then true else false end
            from FriendshipRequest x
            where x.state = dev.hellowrc.circlechat.model.entitiy.FriendshipRequestState.Open
                and x.sender.username = :sender and x.target.username = :target
            """)
    boolean existsOpeningRequests(String sender, String target);

    List<FriendshipRequest> findAllBySender_Username(String username);

    @EntityGraph(attributePaths = {"sender", "target"})
    @Query("""
            select r from FriendshipRequest r
            where r.target.username = :username and (:state is null or r.state = :state)
            """)
    Page<FriendshipRequest> findReceivedRequests(String username, FriendshipRequestState state, Pageable pageable);

    @EntityGraph(attributePaths = {"sender", "target"})
    @Query("""
            select r from FriendshipRequest r
            where r.sender.username = :username and (:state is null or r.state = :state)
            """)
    Page<FriendshipRequest> findSentRequests(String username, FriendshipRequestState state, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from FriendshipRequest r where r.id = :id and r.target.id = :targetId")
    Optional<FriendshipRequest> findReceivedRequestForUpdate(Long id, Long targetId);
}
