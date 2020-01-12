package com.ctrip.framework.apollo.openapi.entity;

import com.ctrip.framework.apollo.common.entity.BaseEntity;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

/**
 * @author Jason Song(song_s@ctrip.com)
 */
@Entity
@Table(name = "ConsumerToken")
@SQLDelete(sql = "Update ConsumerToken set isDeleted = 1 where id = ?")
@Where(clause = "isDeleted = 0")
public class ConsumerToken extends BaseEntity {
  //consumerId 字段，第三方应用编号，指向对应的 Consumer 记录。ConsumerToken 和 Consumer 是多对一的关系
  @Column(name = "ConsumerId", nullable = false)//第三方应用编号
  private long consumerId;

  /**
   token 字段，Token 。

   调用 OpenAPI 时，放在请求 Header "Authorization" 中，作为身份标识。
   通过 ConsumerService#generateToken(consumerAppId, generationTime, consumerTokenSalt) 方法生成
   */
  @Column(name = "token", nullable = false)
  private String token;

  @Column(name = "Expires", nullable = false)//expires 字段，过期时间
  private Date expires;

  public long getConsumerId() {
    return consumerId;
  }

  public void setConsumerId(long consumerId) {
    this.consumerId = consumerId;
  }

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public Date getExpires() {
    return expires;
  }

  public void setExpires(Date expires) {
    this.expires = expires;
  }

  @Override
  public String toString() {
    return toStringHelper().add("consumerId", consumerId).add("token", token)
        .add("expires", expires).toString();
  }
}
