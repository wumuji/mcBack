package com.mcback.service;

import java.util.List;

/**
 * 存档根目录来源。
 *
 * <p>第一阶段的实现是 {@link LauncherDetector};以后如果需要支持自定义规则或别的方式
 * 发现目录,只需新增实现类,{@link WorldScanner} 不需要改动。</p>
 */
public interface WorldRootProvider {

    List<WorldRoot> discover();
}
