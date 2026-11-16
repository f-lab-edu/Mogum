package namingfixture;

import com.mogum.account.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;

@Service
public class NamedDependencies {
    private static final UserRepository shortStatic = null;
    private final UserRepository userRepository;
    private final TransactionTemplate releaseTransactionTemplate;
    private final Clock time;
    private final Map<String, UserRepository> users;
    private final int count;
    private UserRepository shortMutable;

    public NamedDependencies(UserRepository userRepository, TransactionTemplate releaseTransactionTemplate,
            Clock time, Map<String, UserRepository> users, int count) {
        this.userRepository = userRepository;
        this.releaseTransactionTemplate = releaseTransactionTemplate;
        this.time = time;
        this.users = users;
        this.count = count;
    }
}
