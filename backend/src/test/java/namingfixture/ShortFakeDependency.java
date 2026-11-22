package namingfixture;

import org.springframework.web.bind.annotation.RestController;

import com.mogum.account.repository.FakeUserRepository;

@RestController
public class ShortFakeDependency {
	private final FakeUserRepository users = new FakeUserRepository();
}
