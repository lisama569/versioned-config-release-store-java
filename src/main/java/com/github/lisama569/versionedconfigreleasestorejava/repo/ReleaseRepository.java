package com.github.lisama569.versionedconfigreleasestorejava.repo;

import com.github.lisama569.versionedconfigreleasestorejava.domain.Release;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReleaseRepository extends JpaRepository<Release, Long> {

    Optional<Release> findByRequestId(String requestId);

    long countByRequestId(String requestId);

    @Query("select r from Release r where (r.createdAt > :afterTime) "
            + "or (r.createdAt = :afterTime and r.seq > :afterSeq) "
            + "order by r.createdAt asc, r.seq asc")
    List<Release> findPageAfter(@Param("afterTime") Instant afterTime,
                                @Param("afterSeq") long afterSeq,
                                Pageable pageable);

    @Query("select r from Release r order by r.createdAt asc, r.seq asc")
    List<Release> findFirstPage(Pageable pageable);
}
