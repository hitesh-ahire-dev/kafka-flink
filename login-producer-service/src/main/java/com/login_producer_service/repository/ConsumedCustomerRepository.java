package com.login_producer_service.repository;

import com.login_producer_service.entity.ConsumedCustomer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ConsumedCustomerRepository extends JpaRepository<ConsumedCustomer, Long> {
	// Find records consumed after the given timestamp (exclusive)
	java.util.List<ConsumedCustomer> findByConsumedAtAfter(java.time.Instant cutoff);

	/**
	 * Bounded variant: most recent records consumed after the cutoff, ordered desc.
	 * Use Pageable to limit the result set to avoid OOM on busy topics.
	 */
	@Query("select c from ConsumedCustomer c where c.consumedAt > :cutoff order by c.consumedAt desc")
	java.util.List<ConsumedCustomer> findRecent(@Param("cutoff") java.time.Instant cutoff, Pageable pageable);
}

