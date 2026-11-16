package namingfixture;

import com.mogum.account.repository.FakeUserRepository;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShortFakeDependency {
    private final FakeUserRepository users = new FakeUserRepository();
}
