package com.github.lisama569.versionedconfigreleasestorejava.repo;

import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReleaseItemRepository extends JpaRepository<ReleaseItem, Long> {
}
