package repositoryfixture;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mogum.account.domain.User;

// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
public interface BroadUserRepository extends JpaRepository<User, Long> {}
// @checkstyle:on
