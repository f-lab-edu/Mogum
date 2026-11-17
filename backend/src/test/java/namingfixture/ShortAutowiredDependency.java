package namingfixture;

import com.mogum.account.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;

@TestConfiguration(proxyBeanMethods = false)
public class ShortAutowiredDependency {
    @Autowired private final UserRepository users = null;
}
