package com.ctrip.framework.apollo.portal.component.txtresolver;

import com.ctrip.framework.apollo.common.dto.ItemChangeSets;
import com.ctrip.framework.apollo.common.dto.ItemDTO;

import java.util.List;

/**
 * users can modify config in text mode.so need resolve text.
 * 配置文本解析器接口
 * 两个实现类
 * 1、FileTextResolver
 * 2、PropertyResolver
 */
public interface ConfigTextResolver {

  /**
   *
   * @param namespaceId
   * @param configText configText 配置文本
   * @param baseItems baseItems 已存在的 ItemDTO
   * @return
   */
  ItemChangeSets resolve(long namespaceId, String configText, List<ItemDTO> baseItems);

}
