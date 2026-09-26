package com.github.lisama569.versionedconfigreleasestorejava.repo;

import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ConfigEntryRepository extends JpaRepository<ConfigEntry, Long> {

    Optional<ConfigEntry> findByKey(String key);

    boolean existsByKey(String key);

    /**
     * 键集分页：按 (createdAt, seq) 升序取游标之后的一页。
     * 排序键全局唯一，配合单调 seq，持续写入下翻页不会重复或遗漏。
     */
    @Query("select e from ConfigEntry e where (e.createdAt > :afterTime) "
            + "or (e.createdAt = :afterTime and e.seq > :afterSeq) "
            + "order by e.createdAt asc, e.seq asc")
    List<ConfigEntry> findPageAfter(@Param("afterTime") Instant afterTime,
                                    @Param("afterSeq") long afterSeq,
                                    Pageable pageable);

    @Query("select e from ConfigEntry e order by e.createdAt asc, e.seq asc")
    List<ConfigEntry> findFirstPage(Pageable pageable);
}
