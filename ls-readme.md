
# 整体结构

Apollo的总体设计，我们可以从下往上看：

- Config Service提供配置的读取、推送等功能，服务对象是Apollo客户端

- Admin Service提供配置的修改、发布等功能，服务对象是Apollo Portal（管理界面）

- Config Service和Admin Service都是多实例、无状态部署，所以需要将自己注册到Eureka中并保持心跳

- 在Eureka之上我们架了一层Meta Server用于封装Eureka的服务发现接口

- Client通过域名访问Meta Server获取Config Service服务列表（IP+Port），而后直接通过IP+Port访问服务，同时在Client侧会做load balance、错误重试

- Portal通过域名访问Meta Server获取Admin Service服务列表（IP+Port），而后直接通过IP+Port访问服务，同时在Portal侧会做load balance、错误重试

- 为了简化部署，我们实际上会把Config Service、Eureka和Meta Server三个逻辑角色部署在同一个JVM进程中

总结：

简单来说就是Config Service服务和Admin Service服务，通过Eureka注册自己，无论是client还是Portal管理端都是通过注册中心完成自己的功能


# Apollo支持4个维度管理Key-Value格式的配置

- application (应用)：[通过appId标识] ---[appId属性]
- environment (环境)  [对应代码的几套部署环境dev,test,pro]---[env属性]
- cluster (集群) [idc属性]
- namespace (命名空间)[Namespace是配置项的集合，类似于一个配置文件的概念。]


# 数据库位置

D:\IdeaProjects\opensource\apollo\scripts\db\migration\configdb\V1.0.0__initialization.sql

D:\IdeaProjects\opensource\apollo\scripts\db\migration\portaldb\V1.0.0__initialization.sql







# 各模块概要介绍

> 1、Config Service

- 提供配置获取接口
- 提供配置更新推送接口（基于Http long polling）
- 服务端使用Spring DeferredResult实现异步化，从而大大增加长连接数量
- 目前使用的tomcat embed默认配置是最多10000个连接（可以调整），使用了4C8G的虚拟机实测可以支撑10000个连接，所以满足需求（一个应用实例只会发起一个长连接）。
- 接口服务对象为Apollo客户端

> 2、Admin Service

- 提供配置管理接口
- 提供配置修改、发布等接口
- 接口服务对象为Portal[大门的意思，门面]

> 3、Meta Server

- Portal通过域名访问Meta Server获取Admin Service服务列表（IP+Port）
- Client通过域名访问Meta Server获取Config Service服务列表（IP+Port）
- Meta Server从Eureka获取Config Service和Admin Service的服务信息，相当于是一个Eureka Client
- 增设一个Meta Server的角色主要是为了封装服务发现的细节，对Portal和Client而言，永远通过一个Http接口获取Admin Service和Config Service的服务信息，而不需要关心背后实际的服务注册和发现组件
- Meta Server只是一个逻辑角色，在部署时和Config Service是在一个JVM进程中的，所以IP、端口和Config Service一致

> 4、Eureka

- 基于Eureka和Spring Cloud Netflix提供服务注册和发现
- Config Service和Admin Service会向Eureka注册服务，并保持心跳
- 为了简单起见，目前Eureka在部署时和Config Service是在一个JVM进程中的（通过Spring Cloud Netflix）

> 5、Portal

- 提供Web界面供用户管理配置
- 通过Meta Server获取Admin Service服务列表（IP+Port），通过IP+Port访问服务
- 在Portal侧做load balance、错误重试

> 6、Client

- Apollo提供的客户端程序，为应用提供配置获取、实时更新等功能
- 通过Meta Server获取Config Service服务列表（IP+Port），通过IP+Port访问服务
- 在Client侧做load balance、错误重试












# apollo-demo

这里是更加appid来关联的

这个模块包含有通过普通的java，spring的Javabean，注解、xml以及springboot等方式来更新配置信息，
不但支持简单的key:value还支持复杂对象配置





# 

- [VO、DTO、DO、PO的概念、区别和用处](https://www.cnblogs.com/qixuejia/p/4390086.html)

概念：

VO（View Object）：视图对象，用于展示层，它的作用是把某个指定页面（或组件）的所有数据封装起来。

DTO（Data Transfer Object）：数据传输对象，这个概念来源于J2EE的设计模式，原来的目的是为了EJB的分布式应用提供粗粒度的数据实体，以减少分布式调用的次数，从而提高分布式调用的性能和降低网络负载，但在这里，我泛指用于展示层与服务层之间的数据传输对象。

DO（Domain Object）：领域对象，就是从现实世界中抽象出来的有形或无形的业务实体。

PO（Persistent Object）：持久化对象，它跟持久层（通常是关系型数据库）的数据结构形成一一对应的映射关系，如果持久层是关系型数据库，那么，数据表中的每个字段（或若干个）就对应PO的一个（或若干个）属性。



- 用户发出请求（可能是填写表单），表单的数据在展示层被匹配为VO。

- 展示层把VO转换为服务层对应方法所要求的DTO，传送给服务层。

- 服务层首先根据DTO的数据构造（或重建）一个DO，调用DO的业务方法完成具体业务。

- 服务层把DO转换为持久层对应的PO（可以使用ORM工具，也可以不用），调用持久层的持久化方法，把PO传递给它，完成持久化操作。

- 对于一个逆向操作，如读取数据，也是用类似的方式转换和传递，略。



# 参考

- [本地启动](https://blog.csdn.net/michael_hm/article/details/80310606)

- [Apollo开发指南-本地启动](https://github.com/ctripcorp/apollo/wiki/Apollo%E5%BC%80%E5%8F%91%E6%8C%87%E5%8D%97)

- [Apollo配置中心介绍](https://github.com/ctripcorp/apollo/wiki/Apollo%E9%85%8D%E7%BD%AE%E4%B8%AD%E5%BF%83%E4%BB%8B%E7%BB%8D)

- [apollo/wiki](https://github.com/ctripcorp/apollo/wiki/)

- [通过spring提供的DeferredResult实现长轮询服务端推送消息](https://blog.csdn.net/xiao_jun_0820/article/details/82956593)

- [使用案例](https://github.com/ctripcorp/apollo-use-cases)



