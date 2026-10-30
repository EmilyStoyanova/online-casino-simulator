package archfixture.user.controller;

import archfixture.user.dto.UserDto;
import archfixture.user.service.UserService;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    public UserDto get() {
        return userService.find();
    }
}
