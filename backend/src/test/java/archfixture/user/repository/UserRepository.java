package archfixture.user.repository;

import archfixture.user.entity.UserEntity;
import org.springframework.data.repository.Repository;

public interface UserRepository extends Repository<UserEntity, Long> {

    UserEntity findFirst();
}
