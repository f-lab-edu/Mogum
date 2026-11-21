package namingfixture;

import org.springframework.stereotype.Component;

import com.mogum.account.repository.UserRepository;

@Component
public class ShortDependency {
	private final UserRepository users;

	public ShortDependency(UserRepository userRepository) {
		this.users = userRepository;
	}
}
