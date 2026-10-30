package archfixture.user.service;

import archfixture.user.dto.UserDto;
import archfixture.user.entity.UserEntity;
import archfixture.user.repository.UserRepository;

public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserDto find() {
        UserEntity entity = userRepository.findFirst();
        return new UserDto(entity.name(), entity.role());
    }
}
