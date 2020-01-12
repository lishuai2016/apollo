package com.ctrip.framework.apollo.common.dto;

import java.util.LinkedList;
import java.util.List;

/**
 * storage cud result
 Item 的批量变更。

 1、对于 yaml yml json xml 数据类型的 Namespace ，仅有一条 Item 记录，所以批量修改实际是修改该条 Item 。
 2、对于 properties 数据类型的 Namespace ，有多条 Item 记录，所以批量变更是多条 Item
 */
public class ItemChangeSets extends BaseDTO{

  private List<ItemDTO> createItems = new LinkedList<>();
  private List<ItemDTO> updateItems = new LinkedList<>();
  private List<ItemDTO> deleteItems = new LinkedList<>();

  public void addCreateItem(ItemDTO item) {
    createItems.add(item);
  }

  public void addUpdateItem(ItemDTO item) {
    updateItems.add(item);
  }

  public void addDeleteItem(ItemDTO item) {
    deleteItems.add(item);
  }

  public boolean isEmpty(){
    return createItems.isEmpty() && updateItems.isEmpty() && deleteItems.isEmpty();
  }

  public List<ItemDTO> getCreateItems() {
    return createItems;
  }

  public List<ItemDTO> getUpdateItems() {
    return updateItems;
  }

  public List<ItemDTO> getDeleteItems() {
    return deleteItems;
  }

  public void setCreateItems(List<ItemDTO> createItems) {
    this.createItems = createItems;
  }

  public void setUpdateItems(List<ItemDTO> updateItems) {
    this.updateItems = updateItems;
  }

  public void setDeleteItems(List<ItemDTO> deleteItems) {
    this.deleteItems = deleteItems;
  }

}
