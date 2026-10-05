package dev.hellowrc.circlechat.model.entitiy;

import dev.hellowrc.circlechat.abstraction.model.entity.EntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "conversations")
public class Conversation extends EntityBase {

}
