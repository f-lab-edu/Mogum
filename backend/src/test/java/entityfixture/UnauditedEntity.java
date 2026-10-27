package entityfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class UnauditedEntity {
    @Id
    private Long id;
}
