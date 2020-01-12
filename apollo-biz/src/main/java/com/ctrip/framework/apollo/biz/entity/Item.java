package com.ctrip.framework.apollo.biz.entity;

import com.ctrip.framework.apollo.common.entity.BaseEntity;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Lob;
import javax.persistence.Table;

/**
tem ，配置项，是 Namespace 下最小颗粒度的单位。在 Namespace 分成五种类型：properties yml yaml json xml 。其中：

 properties ：每一行配置对应一条 Item 记录。
 后四者：无法进行拆分，所以一个 Namespace 仅仅对应一条 Item 记录

 namespaceId 字段，Namespace 编号，指向对应的 Namespace 记录。

 key 字段，键。
    对于 properties ，使用 Item 的 key ，对应每条配置项的键。
    对于 yaml 等等，使用 Item 的 key = content ，对应整个配置文件。

 lineNum 字段，行号，从一开始。主要用于 properties 类型的配置文件。
 */

@Entity
@Table(name = "Item")
@SQLDelete(sql = "Update Item set isDeleted = 1 where id = ?")
@Where(clause = "isDeleted = 0")
public class Item extends BaseEntity {

  @Column(name = "NamespaceId", nullable = false)
  private long namespaceId;

  @Column(name = "key", nullable = false)
  private String key;

  @Column(name = "value")
  @Lob
  private String value;

  @Column(name = "comment")
  private String comment;

  @Column(name = "LineNum")
  private Integer lineNum;

  public String getComment() {
    return comment;
  }

  public String getKey() {
    return key;
  }

  public long getNamespaceId() {
    return namespaceId;
  }

  public String getValue() {
    return value;
  }

  public void setComment(String comment) {
    this.comment = comment;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public void setNamespaceId(long namespaceId) {
    this.namespaceId = namespaceId;
  }

  public void setValue(String value) {
    this.value = value;
  }

  public Integer getLineNum() {
    return lineNum;
  }

  public void setLineNum(Integer lineNum) {
    this.lineNum = lineNum;
  }

  public String toString() {
    return toStringHelper().add("namespaceId", namespaceId).add("key", key).add("value", value)
        .add("lineNum", lineNum).add("comment", comment).toString();
  }
}
