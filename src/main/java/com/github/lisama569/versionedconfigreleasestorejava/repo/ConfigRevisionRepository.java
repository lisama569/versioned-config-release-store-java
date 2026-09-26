package com.github.lisama569.versionedconfigreleasestorejava.repo;

import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigRevision;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConfigRevisionRepository extends JpaRepository<ConfigRevision, Long> {

    /**
     * 单个条目的版本时间线。该条目的版本记录按其写入顺序追加、seq 严格递增，
     * 因此单条目内直接以 seq 为键集游标；不能用全局 (createdAt, seq)，
     * 否则其他更早日条目的记录会落入窗口造成跨条目串页。
     */
    @Query("select r from ConfigRevision r where r.entrySeq = :entrySeq and r.seq > :afterSeq "
            + "order by r.seq asc")
    List<ConfigRevision> findEntryPageAfter(@Param("entrySeq") long entrySeq,
                                            @Param("afterSeq") long afterSeq,
                                            Pageable pageable);

    @Query("select r from ConfigRevision r where r.entrySeq = :entrySeq order by r.seq asc")
    List<ConfigRevision> findEntryFirstPage(@Param("entrySeq") long entrySeq, Pageable pageable);

    List<ConfigRevision> findByEntrySeqOrderBySeqAsc(long entrySeq);
}
