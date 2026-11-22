package namingfixture;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.bind.annotation.RestController;

import com.mogum.account.repository.FakeUserRepository;
import com.mogum.account.repository.UserRepository;

public class NamedTestDependencies {
	@Autowired
	private UserRepository primaryUserRepository;
	@Autowired
	private UserRepository otherUserRepository;
	private final FakeUserRepository userRepository = new FakeUserRepository();
	private final FakeUserRepository secondaryUserRepository = new FakeUserRepository();
	@Autowired
	private Clock time;
	@Autowired
	private int count;

	@TestConfiguration(proxyBeanMethods = false)
	public static class NamedConfiguration {
		private final UserRepository userRepository = null;
	}

	@RestController
	public static class NamedController {
		private final UserRepository otherUserRepository = null;
	}
}
