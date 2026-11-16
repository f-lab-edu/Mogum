package namingfixture;

import com.mogum.account.repository.UserRepository;
import org.springframework.stereotype.Component;

@Component
public class ShortDependency {
    private final UserRepository users;

    public ShortDependency(UserRepository users) {
        this.users = users;
    }
}
