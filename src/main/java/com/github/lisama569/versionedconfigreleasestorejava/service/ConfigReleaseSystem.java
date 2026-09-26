package com.github.lisama569.versionedconfigreleasestorejava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.versionedconfigreleasestorejava.persistence.EventJournal;
import com.github.lisama569.versionedconfigreleasestorejava.persistence.FileEventJournal;
import com.github.lisama569.versionedconfigreleasestorejava.persistence.InMemoryEventJournal;
import com.github.lisama569.versionedconfigreleasestorejava.store.ConfigStore;
import com.github.lisama569.versionedconfigreleasestorejava.store.ReleaseStore;
import com.github.lisama569.versionedconfigreleasestorejava.store.StoreCoordinator;
import com.github.lisama569.versionedconfigreleasestorejava.support.Clock;
import com.github.lisama569.versionedconfigreleasestorejava.support.SystemClock;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 装配门面：把存储协调器、两个存储、事件日志与四个服务组装为一个可直接使用的系统，
 * 并在构造时执行一次日志恢复。
 *
 * <p>提供两种工厂：
 * <ul>
 *   <li>{@link #inMemory()}：纯内存日志，用于本地可重复单测，不依赖任何外部服务；</li>
 *   <li>{@link #fileBacked(Path)}：文件型仅追加日志，用于演示跨重启持久化。</li>
 * </ul>
 */
public final class ConfigReleaseSystem {

    private final StoreCoordinator coordinator;
    private final ConfigStore configStore;
    private final ReleaseStore releaseStore;
    private final EventJournal journal;
    private final Clock clock;

    private final ConfigService configService;
    private final ReleaseService releaseService;
    private final QueryService queryService;
    private final RecoveryService recoveryService;

    private ConfigReleaseSystem(StoreCoordinator coordinator,
                                ConfigStore configStore,
                                ReleaseStore releaseStore,
                                EventJournal journal,
                                Clock clock) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.releaseStore = Objects.requireNonNull(releaseStore, "releaseStore");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.configService = new ConfigService(coordinator, configStore, releaseStore, journal, clock);
        this.releaseService = new ReleaseService(coordinator, configStore, releaseStore, journal, clock);
        this.queryService = new QueryService(configStore, releaseStore);
        this.recoveryService = new RecoveryService(coordinator, configStore, releaseStore, journal);
        // 装配即恢复：保证对外提供服务前内存态与日志一致
        this.recoveryService.recover();
    }

    /** 纯内存系统（默认系统时钟）。 */
    public static ConfigReleaseSystem inMemory() {
        return inMemory(new SystemClock());
    }

    /** 纯内存系统（注入时钟，供确定性测试）。 */
    public static ConfigReleaseSystem inMemory(Clock clock) {
        return new ConfigReleaseSystem(new StoreCoordinator(), new ConfigStore(),
                new ReleaseStore(), new InMemoryEventJournal(), clock);
    }

    /** 文件日志系统（默认系统时钟）。 */
    public static ConfigReleaseSystem fileBacked(Path journalFile) {
        return fileBacked(journalFile, new SystemClock());
    }

    /** 文件日志系统（注入时钟）。 */
    public static ConfigReleaseSystem fileBacked(Path journalFile, Clock clock) {
        EventJournal journal = new FileEventJournal(journalFile, com.github.lisama569.versionedconfigreleasestorejava.support.Json.mapper());
        return new ConfigReleaseSystem(new StoreCoordinator(), new ConfigStore(),
                new ReleaseStore(), journal, clock);
    }

    public ConfigService configs() {
        return configService;
    }

    public ReleaseService releases() {
        return releaseService;
    }

    public QueryService queries() {
        return queryService;
    }

    public EventJournal journal() {
        return journal;
    }

    public ConfigStore configStore() {
        return configStore;
    }

    public ReleaseStore releaseStore() {
        return releaseStore;
    }

    public StoreCoordinator coordinator() {
        return coordinator;
    }

    public int recoverAgain() {
        return recoveryService.recover();
    }
}
