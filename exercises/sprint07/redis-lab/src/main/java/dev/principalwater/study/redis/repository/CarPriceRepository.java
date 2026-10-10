package dev.principalwater.study.redis.repository;

import dev.principalwater.study.redis.model.CarPrice;
import org.springframework.data.repository.CrudRepository;

public interface CarPriceRepository extends CrudRepository<CarPrice, String> {}
