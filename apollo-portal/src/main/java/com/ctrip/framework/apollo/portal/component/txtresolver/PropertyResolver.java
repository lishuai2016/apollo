package com.ctrip.framework.apollo.portal.component.txtresolver;

import com.ctrip.framework.apollo.common.dto.ItemChangeSets;
import com.ctrip.framework.apollo.common.dto.ItemDTO;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.common.utils.BeanUtils;

import com.google.common.base.Strings;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * normal property file resolver.
 * update comment and blank item implement by create new item and delete old item.
 * update normal key/value item implement by update.
 *
 1、对于注释和空行配置项，基于行数做比较。当发生变化时，使用删除 + 创建的方式。笔者的理解是，
 注释和空行配置项，是没有 Key ，每次变化都认为是新的。另外，这样也可以和注释和空行配置项被改成普通配置项，
 保持一致。例如，第一行原先是注释配置项，改成了普通配置项，从数据上也是删除 + 创建的方式。

 2、对于普通配置项，基于 Key 做比较。例如，第一行原先是普通配置项，结果我们在敲了回车，
 在第一行添加了注释，那么认为是普通配置项修改了行数。
 */
@Component("propertyResolver")
public class PropertyResolver implements ConfigTextResolver {

  private static final String KV_SEPARATOR = "=";
  private static final String ITEM_SEPARATOR = "\n";

  @Override
  public ItemChangeSets resolve(long namespaceId, String configText, List<ItemDTO> baseItems) {

    //按照行号来映射已经存在的数据项
    Map<Integer, ItemDTO> oldLineNumMapItem = BeanUtils.mapByKey("lineNum", baseItems);
    //按照key的格式来映射数据项
    Map<String, ItemDTO> oldKeyMapItem = BeanUtils.mapByKey("key", baseItems);

    //remove comment and blank item map.
    oldKeyMapItem.remove("");

    //按照换行来分割传输的数据
    String[] newItems = configText.split(ITEM_SEPARATOR);

    //重复key的校验
    if (isHasRepeatKey(newItems)) {
      throw new BadRequestException("config text has repeat key please check.");
    }

    ItemChangeSets changeSets = new ItemChangeSets();
    Map<Integer, String> newLineNumMapItem = new HashMap<>();//use for delete blank and comment item
    int lineCounter = 1;
    for (String newItem : newItems) {
      newItem = newItem.trim();
      newLineNumMapItem.put(lineCounter, newItem);//保存行号和记录
      ItemDTO oldItemByLine = oldLineNumMapItem.get(lineCounter);//以前这一行存的记录

      //comment item  注释行
      if (isCommentItem(newItem)) {

        handleCommentLine(namespaceId, oldItemByLine, newItem, lineCounter, changeSets);

        //blank item 空行
      } else if (isBlankItem(newItem)) {

        handleBlankLine(namespaceId, oldItemByLine, lineCounter, changeSets);

        //normal item 正常的行
      } else {
        handleNormalLine(namespaceId, oldKeyMapItem, newItem, lineCounter, changeSets);
      }

      lineCounter++;
    }

    deleteCommentAndBlankItem(oldLineNumMapItem, newLineNumMapItem, changeSets);
    deleteNormalKVItem(oldKeyMapItem, changeSets);

    return changeSets;
  }

  /**
   * 这里通过有效行数和set来实现在O（N）的时间复杂度内找到是否有重复的key
   * @param newItems
   * @return
   */
  private boolean isHasRepeatKey(String[] newItems) {
    Set<String> keys = new HashSet<>();
    int lineCounter = 1;
    int keyCount = 0;
    for (String item : newItems) {
      if (!isCommentItem(item) && !isBlankItem(item)) {//判断注释行和空行
        keyCount++;
        String[] kv = parseKeyValueFromItem(item);//提取key
        if (kv != null) {
          keys.add(kv[0].toLowerCase());
        } else {
          throw new BadRequestException("line:" + lineCounter + " key value must separate by '='");
        }
      }
      lineCounter++;
    }

    return keyCount > keys.size();
  }
  //把数据项按照=分割
  private String[] parseKeyValueFromItem(String item) {
    int kvSeparator = item.indexOf(KV_SEPARATOR);
    if (kvSeparator == -1) {
      return null;
    }

    String[] kv = new String[2];
    kv[0] = item.substring(0, kvSeparator).trim();
    kv[1] = item.substring(kvSeparator + 1, item.length()).trim();
    return kv;
  }
  //处理注释行  只有新旧文件对应的行都是注释时在设置进changeSets
  private void handleCommentLine(Long namespaceId, ItemDTO oldItemByLine, String newItem, int lineCounter, ItemChangeSets changeSets) {
    String oldComment = oldItemByLine == null ? "" : oldItemByLine.getComment();//获取就记录行的注释
    //create comment. implement update comment by delete old comment and create new comment
    if (!(isCommentItem(oldItemByLine) && newItem.equals(oldComment))) {
      changeSets.addCreateItem(buildCommentItem(0l, namespaceId, newItem, lineCounter));
    }
  }

  //新增空白行
  private void handleBlankLine(Long namespaceId, ItemDTO oldItem, int lineCounter, ItemChangeSets changeSets) {
    if (!isBlankItem(oldItem)) {
      changeSets.addCreateItem(buildBlankItem(0l, namespaceId, lineCounter));
    }
  }

  private void handleNormalLine(Long namespaceId, Map<String, ItemDTO> keyMapOldItem, String newItem,
                                int lineCounter, ItemChangeSets changeSets) {

    String[] kv = parseKeyValueFromItem(newItem);//解析这行记录到2个长度的数组

    if (kv == null) {
      throw new BadRequestException("line:" + lineCounter + " key value must separate by '='");
    }

    String newKey = kv[0];
    String newValue = kv[1].replace("\\n", "\n"); //handle user input \n

    ItemDTO oldItem = keyMapOldItem.get(newKey);//key对应的旧记录

    if (oldItem == null) {//new item 不存在，即为新增
      changeSets.addCreateItem(buildNormalItem(0l, namespaceId, newKey, newValue, "", lineCounter));
    } else if (!newValue.equals(oldItem.getValue()) || lineCounter != oldItem.getLineNum()) {//update item  更新
      changeSets.addUpdateItem(
          buildNormalItem(oldItem.getId(), namespaceId, newKey, newValue, oldItem.getComment(),
              lineCounter));
    }
    keyMapOldItem.remove(newKey);//把旧记录中已经处理过的key移除，后面可以用来删除废弃的key
  }

  private boolean isCommentItem(ItemDTO item) {
    return item != null && "".equals(item.getKey())
        && (item.getComment().startsWith("#") || item.getComment().startsWith("!"));
  }
//判断是不是注释行
  private boolean isCommentItem(String line) {
    return line != null && (line.startsWith("#") || line.startsWith("!"));
  }
//判断一行的key和value都是空？？？
  private boolean isBlankItem(ItemDTO item) {
    return item != null && "".equals(item.getKey()) && "".equals(item.getComment());
  }
//判断是不是空行
  private boolean isBlankItem(String line) {
    return  Strings.nullToEmpty(line).trim().isEmpty();
  }
//删除废弃的数据项
  private void deleteNormalKVItem(Map<String, ItemDTO> baseKeyMapItem, ItemChangeSets changeSets) {
    //surplus item is to be deleted
    for (Map.Entry<String, ItemDTO> entry : baseKeyMapItem.entrySet()) {
      changeSets.addDeleteItem(entry.getValue());
    }
  }

  private void deleteCommentAndBlankItem(Map<Integer, ItemDTO> oldLineNumMapItem,
                                         Map<Integer, String> newLineNumMapItem,
                                         ItemChangeSets changeSets) {
    //变量旧的数据集
    for (Map.Entry<Integer, ItemDTO> entry : oldLineNumMapItem.entrySet()) {
      int lineNum = entry.getKey();//行号
      ItemDTO oldItem = entry.getValue();//记录
      String newItem = newLineNumMapItem.get(lineNum);//行对应的新纪录

      //1. old is blank by now is not
      //2.old is comment by now is not exist or modified
      if ((isBlankItem(oldItem) && !isBlankItem(newItem))
          || isCommentItem(oldItem) && (newItem == null || !newItem.equals(oldItem.getComment()))) {
        changeSets.addDeleteItem(oldItem);
      }
    }
  }

  //新增一条注释记录 key和value都是空
  private ItemDTO buildCommentItem(Long id, Long namespaceId, String comment, int lineNum) {
    return buildNormalItem(id, namespaceId, "", "", comment, lineNum);
  }

  private ItemDTO buildBlankItem(Long id, Long namespaceId, int lineNum) {
    return buildNormalItem(id, namespaceId, "", "", "", lineNum);
  }

  private ItemDTO buildNormalItem(Long id, Long namespaceId, String key, String value, String comment, int lineNum) {
    ItemDTO item = new ItemDTO(key, value, comment, lineNum);
    item.setId(id);
    item.setNamespaceId(namespaceId);
    return item;
  }
}
