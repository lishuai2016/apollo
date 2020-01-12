package com.ctrip.framework.apollo.spring.config;

import com.ctrip.framework.apollo.build.ApolloInjector;
import com.ctrip.framework.apollo.spring.property.AutoUpdateConfigChangeListener;
import com.ctrip.framework.apollo.spring.util.SpringInjector;
import com.ctrip.framework.apollo.util.ConfigUtil;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;

import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.ConfigService;

import com.google.common.collect.Sets;
import java.util.List;
import java.util.Set;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import java.util.Collection;
import java.util.Iterator;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Apollo Property Sources processor for Spring Annotation Based Application. <br /> <br />
 *
 * The reason why PropertySourcesProcessor implements {@link BeanFactoryPostProcessor} instead of
 * {@link org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor} is that lower versions of
 * Spring (e.g. 3.1.1) doesn't support registering BeanDefinitionRegistryPostProcessor in ImportBeanDefinitionRegistrar
 * - {@link com.ctrip.framework.apollo.spring.annotation.ApolloConfigRegistrar}
 *
 * @author Jason Song(song_s@ctrip.com)
 */
public class PropertySourcesProcessor implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {
  /**
   * Namespace 名字集合
   *
   * KEY：优先级
   * VALUE：Namespace 名字集合
   */
  private static final Multimap<Integer, String> NAMESPACE_NAMES = LinkedHashMultimap.create();
  private static final Set<BeanFactory> AUTO_UPDATE_INITIALIZED_BEAN_FACTORIES = Sets.newConcurrentHashSet();

  /**
   configPropertySourceFactory 属性，ConfigPropertySource 工厂。在 NAMESPACE_NAMES 中的每一个 Namespace ，
   都会创建成对应的 ConfigPropertySource 对象( 基于 Apollo Config 的 PropertySource 实现类 )，
   并添加到 environment 中。重点：通过这样的方式，Spring 的 <property name="" value="" /> 和 @Value 注解，
   就可以从 environment 中，直接读取到对应的属性值
   */
  private final ConfigPropertySourceFactory configPropertySourceFactory = SpringInjector
      .getInstance(ConfigPropertySourceFactory.class);
  private final ConfigUtil configUtil = ApolloInjector.getInstance(ConfigUtil.class);
  private ConfigurableEnvironment environment;

  /**
   Apollo 在解析到的 XML 或注解配置的 Apollo Namespace 时，会调用 #addNamespaces(namespaces, order) 方法，添加到其中
   * @param namespaces
   * @param order
   * @return
   */
  public static boolean addNamespaces(Collection<String> namespaces, int order) {
    return NAMESPACE_NAMES.putAll(order, namespaces);
  }

  /**
   实现 BeanFactoryPostProcessor 接口，可以在 spring 的 bean 创建之前，修改 bean 的定义属性。
   也就是说，Spring 允许 BeanFactoryPostProcessor 在容器实例化任何其它bean 之前读取配置元数据，
   并可以根据需要进行修改，例如可以把 bean 的 scope 从 singleton 改为 prototype ，
   也可以把 property 的值给修改掉。可以同时配置多个BeanFactoryPostProcessor ，
   并通过设置 order 属性来控制各个BeanFactoryPostProcessor 的执行次序。

   注意：BeanFactoryPostProcessor 是在 spring 容器加载了 bean 的定义文件之后，在 bean 实例化之前执行的。
   * @param beanFactory
   * @throws BeansException
   */
  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
    // 初始化 PropertySource 们
    initializePropertySources();
    // 初始化 AutoUpdateConfigChangeListener 对象，实现属性的自动更新
    initializeAutoUpdatePropertiesFeature(beanFactory);
  }

  private void initializePropertySources() {
    // 若 `environment` 已经有 APOLLO_PROPERTY_SOURCE_NAME 属性源，说明已经初始化，直接返回
    if (environment.getPropertySources().contains(PropertySourcesConstants.APOLLO_PROPERTY_SOURCE_NAME)) {
      //already initialized
      return;
    }
    // 创建 CompositePropertySource 对象，组合多个 Namespace 的 ConfigPropertySource 。
    CompositePropertySource composite = new CompositePropertySource(PropertySourcesConstants.APOLLO_PROPERTY_SOURCE_NAME);

    // 按照优先级，顺序遍历 Namespace
    //sort by order asc
    ImmutableSortedSet<Integer> orders = ImmutableSortedSet.copyOf(NAMESPACE_NAMES.keySet());
    Iterator<Integer> iterator = orders.iterator();

    while (iterator.hasNext()) {
      int order = iterator.next();
      for (String namespace : NAMESPACE_NAMES.get(order)) {
        // 创建 Apollo Config 对象
        Config config = ConfigService.getConfig(namespace);
// 创建 Namespace 对应的 ConfigPropertySource 对象
        composite.addPropertySource(configPropertySourceFactory.getConfigPropertySource(namespace, config));
      }
    }

    // clean up
    NAMESPACE_NAMES.clear();

    /**
     添加 composite 到 environment 中。这样，我们从 environment 里，可以读取到 Apollo Config 的配置。🙂
     属性源的优先级为：APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME > APOLLO_PROPERTY_SOURCE_NAME > 其他。
     APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME 是外部化配置产生的 PropertySource 对象，优先级最高。
     */

    // 若有 APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME 属性源，添加到其后
    // add after the bootstrap property source or to the first
    if (environment.getPropertySources()
        .contains(PropertySourcesConstants.APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME)) {

      // ensure ApolloBootstrapPropertySources is still the first
      ensureBootstrapPropertyPrecedence(environment);

      environment.getPropertySources()
          .addAfter(PropertySourcesConstants.APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME, composite);
    } else {
      // 若没 APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME 属性源，添加到首个
      environment.getPropertySources().addFirst(composite);
    }
  }

  private void ensureBootstrapPropertyPrecedence(ConfigurableEnvironment environment) {
    MutablePropertySources propertySources = environment.getPropertySources();

    PropertySource<?> bootstrapPropertySource = propertySources
        .get(PropertySourcesConstants.APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME);

    // not exists or already in the first place
    if (bootstrapPropertySource == null || propertySources.precedenceOf(bootstrapPropertySource) == 0) {
      return;
    }

    propertySources.remove(PropertySourcesConstants.APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME);
    propertySources.addFirst(bootstrapPropertySource);
  }

  /**
   初始化 AutoUpdateConfigChangeListener 对象，实现 Spring Placeholder 的自动更新功能
   * @param beanFactory
   */
  private void initializeAutoUpdatePropertiesFeature(ConfigurableListableBeanFactory beanFactory) {
    // 若未开启属性的自动更新功能，直接返回.默认开启
    if (!configUtil.isAutoUpdateInjectedSpringPropertiesEnabled() ||
        !AUTO_UPDATE_INITIALIZED_BEAN_FACTORIES.add(beanFactory)) {
      return;
    }
    // 创建 AutoUpdateConfigChangeListener 对象
    AutoUpdateConfigChangeListener autoUpdateConfigChangeListener = new AutoUpdateConfigChangeListener(
        environment, beanFactory);
// 循环，向 ConfigPropertySource 注册配置变更器
    List<ConfigPropertySource> configPropertySources = configPropertySourceFactory.getAllConfigPropertySources();
    for (ConfigPropertySource configPropertySource : configPropertySources) {
      configPropertySource.addChangeListener(autoUpdateConfigChangeListener);
    }
  }

  /**
   environment 属性，Spring ConfigurableEnvironment 对象，
   通过它，可以获取到应用实例中，所有的配置属性信息。通过 #setEnvironment(Environment) 方法，注入
   * @param environment
   */
  @Override
  public void setEnvironment(Environment environment) {
    //it is safe enough to cast as all known environment is derived from ConfigurableEnvironment
    this.environment = (ConfigurableEnvironment) environment;
  }

  @Override
  public int getOrder() {
    //make it as early as possible
    return Ordered.HIGHEST_PRECEDENCE;
  }

  // for test only
  static void reset() {
    NAMESPACE_NAMES.clear();
    AUTO_UPDATE_INITIALIZED_BEAN_FACTORIES.clear();
  }
}
