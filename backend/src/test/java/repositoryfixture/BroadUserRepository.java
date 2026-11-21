package repositoryfixture;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mogum.account.domain.User;

public interface BroadUserRepository extends JpaRepository<User, Long> {}
