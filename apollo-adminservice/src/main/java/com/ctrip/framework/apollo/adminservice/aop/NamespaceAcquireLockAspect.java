package com.ctrip.framework.apollo.adminservice.aop;


import com.ctrip.framework.apollo.biz.config.BizConfig;
import com.ctrip.framework.apollo.biz.entity.Item;
import com.ctrip.framework.apollo.biz.entity.Namespace;
import com.ctrip.framework.apollo.biz.entity.NamespaceLock;
import com.ctrip.framework.apollo.biz.service.ItemService;
import com.ctrip.framework.apollo.biz.service.NamespaceLockService;
import com.ctrip.framework.apollo.biz.service.NamespaceService;
import com.ctrip.framework.apollo.common.dto.ItemChangeSets;
import com.ctrip.framework.apollo.common.dto.ItemDTO;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.common.exception.ServiceException;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;


/**
 * 加锁
 * 一个namespace在一次发布中只能允许一个人修改配置
 * 通过数据库lock表来实现【通过唯一索引实现】
 */
@Aspect
@Component
public class NamespaceAcquireLockAspect {
  private static final Logger logger = LoggerFactory.getLogger(NamespaceAcquireLockAspect.class);

  private final NamespaceLockService namespaceLockService;
  private final NamespaceService namespaceService;
  private final ItemService itemService;
  private final BizConfig bizConfig;

  public NamespaceAcquireLockAspect(
      final NamespaceLockService namespaceLockService,
      final NamespaceService namespaceService,
      final ItemService itemService,
      final BizConfig bizConfig) {
    this.namespaceLockService = namespaceLockService;
    this.namespaceService = namespaceService;
    this.itemService = itemService;
    this.bizConfig = bizConfig;
  }


  //create item
  @Before("@annotation(PreAcquireNamespaceLock) && args(appId, clusterName, namespaceName, item, ..)")
  public void requireLockAdvice(String appId, String clusterName, String namespaceName,
                                ItemDTO item) {
    acquireLock(appId, clusterName, namespaceName, item.getDataChangeLastModifiedBy());
  }

  //update item
  @Before("@annotation(PreAcquireNamespaceLock) && args(appId, clusterName, namespaceName, itemId, item, ..)")
  public void requireLockAdvice(String appId, String clusterName, String namespaceName, long itemId,
                                ItemDTO item) {
    acquireLock(appId, clusterName, namespaceName, item.getDataChangeLastModifiedBy());
  }

  //update by change set
  @Before("@annotation(PreAcquireNamespaceLock) && args(appId, clusterName, namespaceName, changeSet, ..)")
  public void requireLockAdvice(String appId, String clusterName, String namespaceName,
                                ItemChangeSets changeSet) {
    acquireLock(appId, clusterName, namespaceName, changeSet.getDataChangeLastModifiedBy());
  }

  //delete item
  @Before("@annotation(PreAcquireNamespaceLock) && args(itemId, operator, ..)")
  public void requireLockAdvice(long itemId, String operator) {
    Item item = itemService.findOne(itemId);
    if (item == null){
      throw new BadRequestException("item not exist.");
    }
    acquireLock(item.getNamespaceId(), operator);
  }

  void acquireLock(String appId, String clusterName, String namespaceName,
                           String currentUser) {
    // 当关闭锁定 Namespace 开关时，直接返回
    if (bizConfig.isNamespaceLockSwitchOff()) {//配置是否开启锁
      return;
    }

    Namespace namespace = namespaceService.findOne(appId, clusterName, namespaceName);//获得一个namespace实例

    acquireLock(namespace, currentUser);//获取锁
  }

  void acquireLock(long namespaceId, String currentUser) {
    if (bizConfig.isNamespaceLockSwitchOff()) {
      return;
    }

    Namespace namespace = namespaceService.findOne(namespaceId);

    acquireLock(namespace, currentUser);

  }

  /**
   * 根据namespace和当前用户获得锁
   *
   * 发生 DataIntegrityViolationException 异常，说明保存 NamespaceLock 对象失败，由于唯一索引 namespaceId 冲突，
   * 调用 NamespaceLockService#tryLock(NamespaceLock) 方法，获得最新的 NamespaceLock 对象。
   *
   * @param namespace
   * @param currentUser
   */
  private void acquireLock(Namespace namespace, String currentUser) {
    if (namespace == null) {
      throw new BadRequestException("namespace not exist.");
    }

    long namespaceId = namespace.getId();

    //这里使用的是数据库锁，去数据库获得锁
    NamespaceLock namespaceLock = namespaceLockService.findLock(namespaceId);
    //如果为null说明没有人使用这个锁，自己可以使用
    if (namespaceLock == null) {
      try {
        tryLock(namespaceId, currentUser);
        //lock success
      } catch (DataIntegrityViolationException e) {
        //lock fail
        // 锁定失败，获得 NamespaceLock 对象
        namespaceLock = namespaceLockService.findLock(namespaceId);
        // 校验锁定人是否是当前管理员
        checkLock(namespace, namespaceLock, currentUser);
      } catch (Exception e) {
        logger.error("try lock error", e);
        throw e;
      }
    } else {
      //check lock owner is current user 检查是不是自己拿到的锁，锁的可重入性
      checkLock(namespace, namespaceLock, currentUser);
    }
  }

  /**
   * 通过向数据库插入一条记录来实现加锁，然后通过锁的创建者是不是自己，实现可重入性
   * @param namespaceId
   * @param user
   */
  private void tryLock(long namespaceId, String user) {
    NamespaceLock lock = new NamespaceLock();
    lock.setNamespaceId(namespaceId);
    lock.setDataChangeCreatedBy(user);
    lock.setDataChangeLastModifiedBy(user);
    namespaceLockService.tryLock(lock);
  }

  /**
   * 查看锁的创建者是不是自己来判断
   * @param namespace
   * @param namespaceLock
   * @param currentUser
   */
  private void checkLock(Namespace namespace, NamespaceLock namespaceLock,
                         String currentUser) {
    // 当 NamespaceLock 不存在，抛出 ServiceException 异常
    if (namespaceLock == null) {
      throw new ServiceException(
          String.format("Check lock for %s failed, please retry.", namespace.getNamespaceName()));
    }

    // 校验锁定人是否是当前管理员。若不是，抛出 BadRequestException 异常
    String lockOwner = namespaceLock.getDataChangeCreatedBy();
    if (!lockOwner.equals(currentUser)) {
      throw new BadRequestException(
          "namespace:" + namespace.getNamespaceName() + " is modified by " + lockOwner);
    }
  }


}
