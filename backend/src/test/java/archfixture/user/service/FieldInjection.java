package archfixture.user.service;

import archfixture.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** Violates R10. */
public class FieldInjection {

    @Autowired
    private UserRepository userRepository;

    public Object load() {
        return userRepository.findFirst();
    }
}
