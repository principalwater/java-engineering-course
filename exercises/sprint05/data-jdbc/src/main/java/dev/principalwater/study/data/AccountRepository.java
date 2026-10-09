package dev.principalwater.study.data;

import java.math.BigDecimal;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends ListCrudRepository<Account, Long> {
    @Modifying
    @Query("UPDATE account SET balance = balance + :delta WHERE id = :id")
    int adjustBalance(@Param("id") Long id, @Param("delta") BigDecimal delta);
}
