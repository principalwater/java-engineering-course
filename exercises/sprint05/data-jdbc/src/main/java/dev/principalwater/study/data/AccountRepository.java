package dev.principalwater.study.data;

import org.springframework.data.repository.ListCrudRepository;

public interface AccountRepository extends ListCrudRepository<Account, Long> {}
