package com.github.lisama569.versionedconfigreleasestorejava.repo;

import com.github.lisama569.versionedconfigreleasestorejava.domain.Release;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseState;
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

    /**
     * 到期回滚候选：已发布、尚未回滚、声明了绝对到期时刻且已到点。
     * 按到期时刻升序取最紧迫的一批，供到期扫描做有界处理。
     */
    @Query("select r from Release r where r.state = :state and r.expireAt is not null "
            + "and r.expireAt <= :now order by r.expireAt asc, r.seq asc")
    List<Release> findDueRollbacks(@Param("state") ReleaseState state,
                                   @Param("now") Instant now,
                                   Pageable pageable);

    /** 生效候选：已提交、尚未发布、声明了生效时刻且时刻已到。 */
    @Query("select r from Release r where r.state = :state and r.effectiveAt is not null "
            + "and r.effectiveAt <= :now order by r.effectiveAt asc, r.seq asc")
    List<Release> findDuePublishes(@Param("state") ReleaseState state,
                                   @Param("now") Instant now,
                                   Pageable pageable);

    /** "即将到期"首页：已发布未回滚、有绝对到期时刻，按 (expireAt, seq) 升序。 */
    @Query("select r from Release r where r.state = :state and r.expireAt is not null "
            + "order by r.expireAt asc, r.seq asc")
    List<Release> findActiveWindowFirstPage(@Param("state") ReleaseState state,
                                            Pageable pageable);

    /** "即将到期"翻页：严格大于游标 (expireAt, seq)，并以创建 seq 上限冻结遍历宇宙。 */
    @Query("select r from Release r where r.state = :state and r.expireAt is not null "
            + "and r.seq <= :maxSeq "
            + "and (r.expireAt > :afterExpire "
            + "     or (r.expireAt = :afterExpire and r.seq > :afterSeq)) "
            + "order by r.expireAt asc, r.seq asc")
    List<Release> findActiveWindowPageAfter(@Param("state") ReleaseState state,
                                            @Param("afterExpire") Instant afterExpire,
                                            @Param("afterSeq") long afterSeq,
                                            @Param("maxSeq") long maxSeq,
                                            Pageable pageable);

    /** 自动已回滚首页：触发来源 AUTO 的终态发布单，按创建 seq 升序。 */
    @Query("select r from Release r where r.state = :state and r.rollbackTrigger = :trigger "
            + "order by r.seq asc")
    List<Release> findAutoRolledBackFirstPage(@Param("state") ReleaseState state,
                                              @Param("trigger") com.github.lisama569.versionedconfigreleasestorejava.domain.RollbackTrigger trigger,
                                              Pageable pageable);

    /** 自动已回滚翻页：seq 严格大于游标。 */
    @Query("select r from Release r where r.state = :state and r.rollbackTrigger = :trigger "
            + "and r.seq > :afterSeq order by r.seq asc")
    List<Release> findAutoRolledBackPageAfter(@Param("state") ReleaseState state,
                                              @Param("trigger") com.github.lisama569.versionedconfigreleasestorejava.domain.RollbackTrigger trigger,
                                              @Param("afterSeq") long afterSeq,
                                              Pageable pageable);

    /** 遍历起点的创建 seq 上界（首页调用一次，冻结本次遍历宇宙）。 */
    @Query("select coalesce(max(r.seq), 0) from Release r")
    long findMaxSeq();

    /** "已到期未回滚"翻页：到期且仍在 PUBLISHED，严格大于游标 (expireAt, seq)，seq 上界冻结宇宙。 */
    @Query("select r from Release r where r.state = :state and r.expireAt is not null "
            + "and r.expireAt <= :now and r.seq <= :maxSeq "
            + "and (r.expireAt > :afterExpire "
            + "     or (r.expireAt = :afterExpire and r.seq > :afterSeq)) "
            + "order by r.expireAt asc, r.seq asc")
    List<Release> findOverduePageAfter(@Param("state") ReleaseState state,
                                       @Param("now") Instant now,
                                       @Param("afterExpire") Instant afterExpire,
                                       @Param("afterSeq") long afterSeq,
                                       @Param("maxSeq") long maxSeq,
                                       Pageable pageable);
}
