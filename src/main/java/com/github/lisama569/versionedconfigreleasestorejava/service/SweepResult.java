package com.github.lisama569.versionedconfigreleasestorejava.service;

import java.util.List;

/**
 * 一次到期/生效扫描的确定结论（日志与测试可直接核对输入与判定）。
 *
 * @param scanned   本次扫描到的候选数量
 * @param succeeded 真正完成状态推进的数量
 * @param replayed  候选已被其他触发者推进、本次以重放方式得到同一结论的数量
 * @param failed    因前置条件等业务原因未推进的数量
 * @param targets   候选 requestId（按处理顺序）
 */
public record SweepResult(int scanned, int succeeded, int replayed, int failed,
                          List<String> targets) {

    public static SweepResult empty() {
        return new SweepResult(0, 0, 0, 0, List.of());
    }
}
