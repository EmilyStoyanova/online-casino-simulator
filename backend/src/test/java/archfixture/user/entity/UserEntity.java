package archfixture.user.entity;

import jakarta.persistence.Entity;

@Entity
public class UserEntity {

    public String name() {
        return "name";
    }

    public UserRole role() {
        return UserRole.PLAYER;
    }
}
