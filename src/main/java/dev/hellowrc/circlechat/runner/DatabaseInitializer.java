package dev.hellowrc.circlechat.runner;

import dev.hellowrc.circlechat.repository.IChatroomsRepository;
import dev.hellowrc.circlechat.repository.IConversationsRepository;
import dev.hellowrc.circlechat.repository.IUsersRepository;
import dev.hellowrc.circlechat.service.ChatroomService;
import dev.hellowrc.circlechat.service.MessageService;
import dev.hellowrc.circlechat.service.UserService;
import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer implements ApplicationRunner {
    private final IConversationsRepository conversationsRepository;
    private final IUsersRepository usersRepository;
    private final UserService userService;
    private final ChatroomService chatroomService;
    private final IChatroomsRepository chatroomsRepository;
    private final EntityManager entityManager;

    public DatabaseInitializer(IConversationsRepository conversationsRepository, IUsersRepository usersRepository, UserService userService,
                               ChatroomService chatroomService, IChatroomsRepository chatroomsRepository,
                               EntityManager entityManager) {
        this.conversationsRepository = conversationsRepository;
        this.usersRepository = usersRepository;
        this.userService = userService;
        this.chatroomService = chatroomService;
        this.chatroomsRepository = chatroomsRepository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void run(@NonNull ApplicationArguments args) {
        if (!usersRepository.existsByUsername(UserService.SUPER_ADMIN_USERNAME)) {
            userService.createDefaultSuperAdmin();
        }

        if (!conversationsRepository.existsById(MessageService.MAIN_CONVERSATION_ID)) {
            // IDENTITY normally starts at 1; the existing chat protocol reserves ID 0.
            entityManager.createNativeQuery("""
                    insert into conversations (id, created_at, updated_at)
                    values (0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """).executeUpdate();
        }
        var admin = usersRepository.findByUsername(UserService.SUPER_ADMIN_USERNAME);
        if (!chatroomsRepository.existsById(ChatroomService.MAIN_CHATROOM_ID)) {
            entityManager.createNativeQuery("""
                    insert into chatrooms (id, owner_id, conversation_id, name, created_at, updated_at)
                    values (0, :ownerId, 0, :name, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """).setParameter("ownerId", admin.getId()).setParameter("name", "主聊天室").executeUpdate();
        }
        chatroomService.addChatroomMemberIfNotExists(
                chatroomsRepository.findById(ChatroomService.MAIN_CHATROOM_ID).orElseThrow(), admin);
    }
}
