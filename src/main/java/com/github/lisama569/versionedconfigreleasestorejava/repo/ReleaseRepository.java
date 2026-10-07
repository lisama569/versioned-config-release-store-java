package com.github.lisama569.versionedconfigreleasestorejava.repo;

import com.github.lisama569.versionedconfigreleasestorejava.domain.Release;
import com.github.lisama569.versionedconfigreleasestorejava.domain.RollbackReason;
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

    /** 已到期待回滚：处于 PUBLISHED 且窗口终点不晚于 now，按 (windowEnd, seq) 稳定排序。 */
    @Query("select r from Release r where r.state = 'PUBLISHED' and r.windowEnd is not null "
            + "and r.windowEnd <= :now order by r.windowEnd asc, r.seq asc")
    List<Release> findExpiredPublished(@Param("now") Instant now, Pageable pageable);

    /**
     * 即将到期：PUBLISHED 且 now < windowEnd <= until。
     * 排序键与主列表一致为 (createdAt, seq)：新写入的发布单排序键必然更大，
     * 持续写入下翻页不重复、不遗漏（若以 windowEnd 为排序键，新单可能携带
     * 比游标更小的终点而被跳过）。
     */
    @Query("select r from Release r where r.state = 'PUBLISHED' and r.windowEnd is not null "
            + "and r.windowEnd > :now and r.windowEnd <= :until "
            + "and (r.createdAt > :afterTime or (r.createdAt = :afterTime and r.seq > :afterSeq)) "
            + "order by r.createdAt asc, r.seq asc")
    List<Release> findExpiringSoon(@Param("now") Instant now, @Param("until") Instant until,
                                   @Param("afterTime") Instant afterTime,
                                   @Param("afterSeq") long afterSeq, Pageable pageable);

    /** 已到期但还没回滚：PUBLISHED 且 windowEnd <= now，键集游标为 (createdAt, seq)。 */
    @Query("select r from Release r where r.state = 'PUBLISHED' and r.windowEnd is not null "
            + "and r.windowEnd <= :now "
            + "and (r.createdAt > :afterTime or (r.createdAt = :afterTime and r.seq > :afterSeq)) "
            + "order by r.createdAt asc, r.seq asc")
    List<Release> findExpiredPendingPage(@Param("now") Instant now,
                                         @Param("afterTime") Instant afterTime,
                                         @Param("afterSeq") long afterSeq, Pageable pageable);

    /** 已被自动回滚：键集游标为 (createdAt, seq)。 */
    @Query("select r from Release r where r.state = 'ROLLED_BACK' "
            + "and r.rollbackReason = :reason "
            + "and (r.createdAt > :afterTime or (r.createdAt = :afterTime and r.seq > :afterSeq)) "
            + "order by r.createdAt asc, r.seq asc")
    List<Release> findRolledBackByReason(@Param("reason") RollbackReason reason,
                                         @Param("afterTime") Instant afterTime,
                                         @Param("afterSeq") long afterSeq, Pageable pageable);
}
