package archfixture.user.service;

import org.springframework.web.bind.annotation.RestController;

/** Violates R5: a controller outside a controller package. */
@RestController
public class MisplacedController {
}
