package com.github.lisama569.versionedconfigreleasestorejava.web;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.EntryView;
import com.github.lisama569.versionedconfigreleasestorejava.api.RevisionView;
import com.github.lisama569.versionedconfigreleasestorejava.api.UpdateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigService;
import com.github.lisama569.versionedconfigreleasestorejava.support.CursorPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/entries")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @PostMapping
    public EntryView create(@RequestBody CreateEntryRequest request) {
        return configService.createEntry(request);
    }

    @PutMapping("/{key}")
    public EntryView update(@PathVariable String key, @RequestBody UpdateEntryRequest request) {
        return configService.updateEntry(key, request);
    }

    @GetMapping("/{key}")
    public EntryView get(@PathVariable String key) {
        return configService.getEntry(key);
    }

    @GetMapping
    public CursorPage<EntryView> list(@RequestParam(required = false) String cursor,
                                      @RequestParam(defaultValue = "50") int limit) {
        return configService.listEntries(cursor, limit);
    }

    @GetMapping("/{key}/revisions")
    public CursorPage<RevisionView> revisions(@PathVariable String key,
                                              @RequestParam(required = false) String cursor,
                                              @RequestParam(defaultValue = "50") int limit) {
        return configService.listRevisions(key, cursor, limit);
    }
}
