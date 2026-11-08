package repositoryfixture;

import com.mogum.account.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BroadUserRepository extends JpaRepository<User, Long> {
}
