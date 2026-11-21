package namingfixture;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;

import com.mogum.account.repository.UserRepository;

@TestConfiguration(proxyBeanMethods = false)
public class ShortAutowiredDependency {
	@Autowired
	private final UserRepository users = null;
}
