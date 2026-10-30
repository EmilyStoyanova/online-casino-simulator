package archfixture.auth.service;

import archfixture.user.repository.UserRepository;

/** Violates R3: uses another module's repository instead of its service. */
public class OtherModuleRepositoryAccess {

    public Object load(UserRepository userRepository) {
        return userRepository.findFirst();
    }
}
