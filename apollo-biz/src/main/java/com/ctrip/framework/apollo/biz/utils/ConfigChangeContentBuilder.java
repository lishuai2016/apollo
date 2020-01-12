package com.ctrip.framework.apollo.biz.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.ctrip.framework.apollo.biz.entity.Item;
import com.ctrip.framework.apollo.core.utils.StringUtils;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import org.springframework.beans.BeanUtils;

/**
 com.ctrip.framework.apollo.biz.entity.Commit#changeSets字符串生成器


 */

public class ConfigChangeContentBuilder {

  private static final Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").create();

  private List<Item> createItems = new LinkedList<>();
  private List<ItemPair> updateItems = new LinkedList<>();
  private List<Item> deleteItems = new LinkedList<>();

//创建 Item 集合
  public ConfigChangeContentBuilder createItem(Item item) {
    if (!StringUtils.isEmpty(item.getKey())){
      createItems.add(cloneItem(item));
    }
    return this;
  }
//更新 Item 集合
  public ConfigChangeContentBuilder updateItem(Item oldItem, Item newItem) {
    if (!oldItem.getValue().equals(newItem.getValue())){
      ItemPair itemPair = new ItemPair(cloneItem(oldItem), cloneItem(newItem));
      updateItems.add(itemPair);
    }
    return this;
  }
//删除 Item 集合
  public ConfigChangeContentBuilder deleteItem(Item item) {
    if (!StringUtils.isEmpty(item.getKey())) {
      deleteItems.add(cloneItem(item));
    }
    return this;
  }
  //判断是否有变化。当且仅当有变化才记录 Commit
  public boolean hasContent(){
    return !createItems.isEmpty() || !updateItems.isEmpty() || !deleteItems.isEmpty();
  }
  //生成对象
  public String build() {
    //因为事务第一段提交并没有更新时间,所以build时统一更新
    Date now = new Date();

    for (Item item : createItems) {
      item.setDataChangeLastModifiedTime(now);//设置最后的修改时间
    }

    for (ItemPair item : updateItems) {
      item.newItem.setDataChangeLastModifiedTime(now);//设置最后的修改时间
    }

    for (Item item : deleteItems) {
      item.setDataChangeLastModifiedTime(now);//设置最后的修改时间
    }
    return gson.toJson(this);
  }

  static class ItemPair {

    Item oldItem;
    Item newItem;

    public ItemPair(Item oldItem, Item newItem) {
      this.oldItem = oldItem;
      this.newItem = newItem;
    }
  }
//克隆 Item 对象。因为在 #build() 方法中，会修改 Item 对象的属性
  Item cloneItem(Item source) {
    Item target = new Item();

    BeanUtils.copyProperties(source, target);

    return target;
  }

}
